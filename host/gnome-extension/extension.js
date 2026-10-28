// Exposes the focused app's name and the mouse pointer position on the session
// bus so tabletdesk_host.py can read them on Wayland, where other programs
// cannot see other windows or the pointer.
import Gio from 'gi://Gio';
import Meta from 'gi://Meta';
import Shell from 'gi://Shell';
import * as Main from 'resource:///org/gnome/shell/ui/main.js';
import {Extension} from 'resource:///org/gnome/shell/extensions/extension.js';

const IFACE = `
<node>
  <interface name="io.github.aliihjt.TabletDesk">
    <method name="FocusedApp">
      <arg type="s" direction="out" name="name"/>
    </method>
    <method name="Pointer">
      <arg type="s" direction="out" name="json"/>
    </method>
    <method name="Windows">
      <arg type="s" direction="out" name="json"/>
    </method>
    <method name="Activate">
      <arg type="s" direction="in" name="id"/>
    </method>
    <method name="Close">
      <arg type="s" direction="in" name="id"/>
    </method>
  </interface>
</node>`;

export default class TabletDeskExtension extends Extension {
    enable() {
        this._dbus = Gio.DBusExportedObject.wrapJSObject(IFACE, this);
        this._dbus.export(Gio.DBus.session, '/io/github/aliihjt/TabletDesk');
    }

    disable() {
        this._dbus?.unexport();
        this._dbus = null;
    }

    FocusedApp() {
        const win = global.display.focus_window;
        if (!win)
            return 'Desktop';
        const app = Shell.WindowTracker.get_default().get_window_app(win);
        return app ? app.get_name() : (win.get_wm_class() ?? 'Desktop');
    }

    // Open windows for the tablet's window switcher, most recently used first.
    Windows() {
        const tracker = Shell.WindowTracker.get_default();
        const focused = global.display.focus_window;
        const list = global.display.get_tab_list(Meta.TabList.NORMAL_ALL, null).map(w => {
            const app = tracker.get_window_app(w);
            return {
                id: String(w.get_id()),
                title: w.get_title() ?? '',
                app: app ? app.get_name() : (w.get_wm_class() ?? ''),
                minimized: w.minimized,
                focused: w === focused,
            };
        });
        return JSON.stringify(list);
    }

    _find(id) {
        return global.display.get_tab_list(Meta.TabList.NORMAL_ALL, null)
            .find(w => String(w.get_id()) === id);
    }

    Activate(id) {
        const w = this._find(id);
        if (w)
            Main.activateWindow(w);
    }

    Close(id) {
        const w = this._find(id);
        if (w)
            w.delete(global.get_current_time());
    }

    // Where the pointer is on the tablet's screen (the non-primary monitor),
    // as fractions 0..1 of its size; '' when it is on the laptop screen.
    Pointer() {
        const monitors = Main.layoutManager.monitors;
        if (monitors.length < 2)
            return '';
        const index = global.display.get_current_monitor();
        if (index === Main.layoutManager.primaryIndex || !monitors[index])
            return '';
        const [x, y] = global.get_pointer();
        const m = monitors[index];
        return JSON.stringify({x: (x - m.x) / m.width, y: (y - m.y) / m.height});
    }
}
