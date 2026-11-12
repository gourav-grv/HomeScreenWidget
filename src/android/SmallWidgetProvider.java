package io.homescreenwidget.plugin;

/** "Small" entry of the widget picker (2x2). Shows the data sent with WidgetData.Size = "small". */
public class SmallWidgetProvider extends GenericWidgetProvider {
    @Override
    String size() { return "small"; }
}
