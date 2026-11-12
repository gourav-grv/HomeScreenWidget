package io.homescreenwidget.plugin;

/** "Large" entry of the widget picker (4x4). Shows the data sent with WidgetData.Size = "large". */
public class LargeWidgetProvider extends GenericWidgetProvider {
    @Override
    String size() { return "large"; }
}
