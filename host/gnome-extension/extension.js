// Exposes the focused app's name and the mouse pointer position on the session
// bus so tabletdesk_host.py can read them on Wayland, where other programs
// cannot see other windows or the pointer.
import Gio from 'gi://Gio';
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
