# cordova-plugin-homescreen-widget

Dynamic home screen widget for Cordova / OutSystems mobile apps: Android App Widget and iOS WidgetKit.
The app sends JSON (header + rows + buttons) and the widget renders it natively.

## Multiple widget types
Each widget type shows up as its own entry in the widget gallery. Declare the types in the app's config.xml (OutSystems: the app's Extensibility Configurations):
```json
{ "preferences": { "global": [ { "name": "HSWWidgetTypes", "value": "pending|Pending Tasks;done|Done Today" } ] } }
```
The format is `key|Label` separated by `;`. A key may contain letters, digits and `_`. Without the preference there is one type, `main`, labelled with the app name.
The build hooks generate one Android provider (`HswWidget_<key>`) and one iOS widget (kind `HSW_<key>`) per type. iOS supports up to 10 types.
Every data call takes the type key: `update(data, "pending")`.

## JavaScript API (`window.HomeScreenWidget`)
| Method | Description |
|---|---|
| `update(data, key)` | Saves the data of one widget type and refreshes it |
| `clear(key)` | Removes the data of one widget type |
| `isSupported()` | Resolves `true` when the widget can be shown |
| `requestPin(key)` | Android 8+: asks the launcher to add that widget type |
| `setHeaders(obj)` | Headers for `http` actions and `RefreshUrl`, e.g. `{ "Authorization": "Bearer …" }` |
| `refresh(key)` | Fetches that type's `RefreshUrl` now |
| `onAction(cb)` | Listener for `clientAction` / `openApp` taps. Returns an unsubscribe function. Taps made while the app was closed are delivered when the first listener registers. |

## Data
```json
{
  "Title": "My Tasks", "Subtitle": "", "Count": 3, "CountLabel": "pending",
  "Footer": "Updated 10:30", "EmptyText": "All done", "DeepLink": "", "AccentColor": "#1068EB",
  "RefreshUrl": "https://server/App/rest/Widget/Data",
  "Rows": [
    { "Id": "12", "Type": "item", "Text": "Buy milk", "Subtext": "Today", "IsDone": false,
      "Buttons": [
        { "Id": "done", "Label": "Done", "ActionType": "clientAction", "ActionName": "ToggleTask", "Payload": "12" },
        { "Id": "srv", "Label": "Sync", "ActionType": "http", "Method": "POST", "Url": "https://…", "Body": "{\"Id\":12}" }
      ] },
    { "Type": "progress", "Text": "Sprint", "Progress": 60 },
    { "Type": "text", "Text": "Some note" },
    { "Type": "image", "ImageUrl": "https://…/chart.png", "Text": "Caption" }
  ]
}
```

## Button actions
| ActionType | What happens |
|---|---|
| `http` | Calls `Url` from the widget with the stored headers, **without opening the app** (Android; iOS 17+). If the response is widget data JSON, the widget shows it; otherwise `RefreshUrl` is re-read. |
| `clientAction` | Android: if the app is running, the action is delivered to `onAction` in the background and the app stays closed. Otherwise the app opens and gets the action on start. iOS: always opens the app. |
| `openApp` | Opens the app (`DeepLink`) and delivers the action to `onAction` (e.g. to navigate to the row). |

Tapping a row sends `{ ActionType: "openApp", ActionName: "RowTap", RowId }`.

## iOS
- The App Group defaults to `group.<bundle id>`. Override it with the config.xml preference `HSWAppGroup`.
- The widget extension (`<bundle id>.HomeWidget`, iOS 15+) is added by `hooks/ios/add_widget_extension.js`. It needs its own provisioning profile with the same App Group (optional preference `HSWWidgetProfile`).
- Widget taps reach the app through the URL scheme `hsw-<app id>`.
