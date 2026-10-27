// Exposes the focused app's name on the session bus so tabletdesk_host.py can
// read it on Wayland, where other programs cannot see other windows.
import Gio from 'gi://Gio';
import Shell from 'gi://Shell';
import {Extension} from 'resource:///org/gnome/shell/extensions/extension.js';

const IFACE = `
<node>
  <interface name="io.github.aliihjt.TabletDesk">
    <method name="FocusedApp">
      <arg type="s" direction="out" name="name"/>
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
}
