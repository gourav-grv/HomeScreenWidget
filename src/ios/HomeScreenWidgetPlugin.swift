import Foundation
import WidgetKit

/// JS bridge: window.HomeScreenWidget.*
/// Data, headers and pending actions are shared with the widget extension through the App Group
/// (Info.plist key HSWAppGroup, written by the plugin hook; default group.<bundle id>).
@objc(HomeScreenWidgetPlugin)
class HomeScreenWidgetPlugin: CDVPlugin {

    static let dataKey = "hsw_data"
    static let headersKey = "hsw_headers"
    static let pendingKey = "hsw_pending"

    private var listenerId: String?

    private var shared: UserDefaults? {
        guard let group = Bundle.main.object(forInfoDictionaryKey: "HSWAppGroup") as? String,
              !group.trimmingCharacters(in: .whitespaces).isEmpty else { return nil }
        return UserDefaults(suiteName: group)
    }

    override func onReset() {
        listenerId = nil
    }

    // MARK: - commands

    @objc(update:)
    func update(_ command: CDVInvokedUrlCommand) {
        guard let defaults = shared else { return fail(command, "App Group is not configured.") }
        let json = (command.argument(at: 0) as? String) ?? "{}"
        guard isJSONObject(json) else { return fail(command, "Payload is not valid JSON.") }
        defaults.set(json, forKey: Self.dataKey)
        reloadWidgets()
        ok(command)
    }

    @objc(clear:)
    func clear(_ command: CDVInvokedUrlCommand) {
        shared?.removeObject(forKey: Self.dataKey)
        reloadWidgets()
        ok(command)
    }

    @objc(setHeaders:)
    func setHeaders(_ command: CDVInvokedUrlCommand) {
        let json = (command.argument(at: 0) as? String) ?? "{}"
        guard isJSONObject(json) else { return fail(command, "Headers are not valid JSON.") }
        shared?.set(json, forKey: Self.headersKey)
        ok(command)
    }

    @objc(refresh:)
    func refresh(_ command: CDVInvokedUrlCommand) {
        guard let defaults = shared,
              let raw = defaults.string(forKey: Self.dataKey),
              let obj = parseObject(raw),
              let url = obj["RefreshUrl"] as? String, !url.isEmpty else {
            reloadWidgets()
            return ok(command)
        }
        performRequest(url: url, method: "GET", body: nil) { [weak self] in
            self?.reloadWidgets()
            self?.ok(command)
        }
    }

    @objc(isSupported:)
    func isSupported(_ command: CDVInvokedUrlCommand) {
        var supported = false
        if #available(iOS 14.0, *) { supported = shared != nil }
        send(command, CDVPluginResult(status: CDVCommandStatus_OK, messageAs: supported))
    }

    /// iOS does not let apps pin widgets; the user adds the widget from the home screen.
    @objc(requestPin:)
    func requestPin(_ command: CDVInvokedUrlCommand) {
        send(command, CDVPluginResult(status: CDVCommandStatus_OK, messageAs: false))
    }

    /// Keeps the callback open; every widget action is sent to it. Pending actions are flushed first.
    @objc(listen:)
    func listen(_ command: CDVInvokedUrlCommand) {
        listenerId = command.callbackId
        let pending = shared?.stringArray(forKey: Self.pendingKey) ?? []
        shared?.removeObject(forKey: Self.pendingKey)
        for raw in pending {
            if let obj = parseObject(raw) { deliver(obj) }
        }
        let keep = CDVPluginResult(status: CDVCommandStatus_NO_RESULT)
        keep?.setKeepCallbackAs(true)
        commandDelegate.send(keep, callbackId: command.callbackId)
    }

    // MARK: - widget taps arrive as hsw-<bundle id>://action?a=<json>

    override func handleOpenURL(_ notification: Notification!) {
        guard let url = notification?.object as? URL,
              let scheme = url.scheme, scheme.hasPrefix("hsw-"),
              let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems,
              let raw = items.first(where: { $0.name == "a" })?.value,
              let action = parseObject(raw) else { return }

        let type = (action["ActionType"] as? String) ?? "openApp"
        if type.caseInsensitiveCompare("http") == .orderedSame {
            // iOS < 17 fallback: the widget opened the app; run the request here.
            if let target = action["Url"] as? String, !target.isEmpty {
                performRequest(url: target, method: action["Method"] as? String, body: action["Body"] as? String) { [weak self] in
                    self?.reloadWidgets()
                }
            }
            return
        }
        let event: [String: Any] = [
            "ActionType": type,
            "ActionName": action["ActionName"] as? String ?? "",
            "RowId": action["RowId"] as? String ?? "",
            "ButtonId": action["ButtonId"] as? String ?? "",
            "Payload": action["Payload"] as? String ?? ""
        ]
        if listenerId != nil {
            deliver(event)
        } else if let data = try? JSONSerialization.data(withJSONObject: event),
                  let str = String(data: data, encoding: .utf8) {
            var pending = shared?.stringArray(forKey: Self.pendingKey) ?? []
            pending.append(str)
            shared?.set(Array(pending.suffix(20)), forKey: Self.pendingKey)
        }
    }

    // MARK: - helpers

    private func deliver(_ event: [String: Any]) {
        guard let id = listenerId else { return }
        let r = CDVPluginResult(status: CDVCommandStatus_OK, messageAs: event)
        r?.setKeepCallbackAs(true)
        commandDelegate.send(r, callbackId: id)
    }

    private func performRequest(url: String, method: String?, body: String?, done: @escaping () -> Void) {
        guard let u = URL(string: url) else { return done() }
        var req = URLRequest(url: u, timeoutInterval: 10)
        let hasBody = !(body ?? "").isEmpty
        req.httpMethod = (method?.isEmpty == false) ? method!.uppercased() : (hasBody ? "POST" : "GET")
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        if hasBody, req.httpMethod != "GET" {
            req.httpBody = body!.data(using: .utf8)
            req.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        }
        if let raw = shared?.string(forKey: Self.headersKey), let headers = parseObject(raw) {
            for (k, v) in headers { req.setValue("\(v)", forHTTPHeaderField: k) }
        }
        URLSession.shared.dataTask(with: req) { [weak self] data, response, _ in
            if let self = self,
               let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode),
               let data = data,
               let text = String(data: data, encoding: .utf8),
               let obj = self.parseObject(text), obj["Rows"] != nil || obj["Title"] != nil {
                self.shared?.set(text, forKey: Self.dataKey)
            }
            DispatchQueue.main.async { done() }
        }.resume()
    }

    private func parseObject(_ raw: String) -> [String: Any]? {
        guard let data = raw.data(using: .utf8) else { return nil }
        return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
    }

    private func isJSONObject(_ raw: String) -> Bool { parseObject(raw) != nil }

    private func reloadWidgets() {
        if #available(iOS 14.0, *) { WidgetCenter.shared.reloadAllTimelines() }
    }

    private func ok(_ command: CDVInvokedUrlCommand) { send(command, CDVPluginResult(status: CDVCommandStatus_OK)) }

    private func fail(_ command: CDVInvokedUrlCommand, _ message: String) {
        send(command, CDVPluginResult(status: CDVCommandStatus_ERROR, messageAs: message))
    }

    private func send(_ command: CDVInvokedUrlCommand, _ result: CDVPluginResult?) {
        commandDelegate.send(result, callbackId: command.callbackId)
    }
}
