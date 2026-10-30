// Exposes the focused app's name and the mouse pointer position on the session
// bus so tabletdesk_host.py can read them on Wayland, where other programs
// cannot see other windows or the pointer.
import Gio from 'gi://Gio';
import GLib from 'gi://GLib';
import Meta from 'gi://Meta';
import Shell from 'gi://Shell';
import St from 'gi://St';
import * as Main from 'resource:///org/gnome/shell/ui/main.js';
import * as Keyboard from 'resource:///org/gnome/shell/ui/status/keyboard.js';
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
    <method name="MoveToTablet">
      <arg type="s" direction="in" name="id"/>
    </method>
    <method name="MoveToLaptop">
      <arg type="s" direction="in" name="id"/>
    </method>
    <method name="Ambient">
      <arg type="s" direction="out" name="json"/>
    </method>
    <method name="SetInputSource">
      <arg type="s" direction="in" name="id"/>
    </method>
    <method name="ClipHistory">
      <arg type="s" direction="out" name="json"/>
    </method>
    <method name="ClipClear">
      <arg type="s" direction="out" name="ok"/>
    </method>
  </interface>
</node>`;

export default class TabletDeskExtension extends Extension {
    enable() {
        this._dbus = Gio.DBusExportedObject.wrapJSObject(IFACE, this);
        this._dbus.export(Gio.DBus.session, '/io/github/aliihjt/TabletDesk');
        // clipboard history for the tablet: the last 50 texts copied, in memory only
        this._clips = [];
        this._selection = global.display.get_selection();
        this._selectionId = this._selection.connect('owner-changed', (sel, type) => {
            if (type === Meta.SelectionType.SELECTION_CLIPBOARD)
                this._rememberClip();
        });
    }

    disable() {
        if (this._selectionId)
            this._selection.disconnect(this._selectionId);
        this._selectionId = 0;
        this._selection = null;
        this._clips = [];
        this._dbus?.unexport();
        this._dbus = null;
        if (this._ambientTimer)
            GLib.source_remove(this._ambientTimer);
        this._ambientTimer = 0;
    }

    // Move a window to the tablet's screen (the non-primary monitor) and fill it,
    // or back to the laptop.
    _moveTo(id, toTablet) {
        const w = this._find(id);
        const monitors = Main.layoutManager.monitors;
        if (!w || monitors.length < 2)
            return;
        const primary = Main.layoutManager.primaryIndex;
        const target = toTablet ? monitors.findIndex((m, i) => i !== primary) : primary;
        w.move_to_monitor(target);
        try {
            w.maximize(Meta.MaximizeFlags.BOTH);
        } catch (e) {
            w.maximize();  // newer GNOME: no flags
        }
        Main.activateWindow(w);
    }

    MoveToTablet(id) {
        this._moveTo(id, true);
    }

    MoveToLaptop(id) {
        this._moveTo(id, false);
    }

    // Ambient light: colours of the laptop screen at 3 x 3 points, sampled
    // a few times a second while the tablet keeps asking (stops 5 s after).
    Ambient() {
        this._ambientAsked = Date.now();
        if (!this._ambientTimer) {
            this._ambientTimer = GLib.timeout_add(GLib.PRIORITY_DEFAULT, 250, () => {
                if (Date.now() - this._ambientAsked > 5000) {
                    this._ambientTimer = 0;
                    return GLib.SOURCE_REMOVE;
                }
                this._sample();
                return GLib.SOURCE_CONTINUE;
            });
        }
        return JSON.stringify(this._ambient ?? []);
    }

    async _sample() {
        if (this._sampling)
            return;
        this._sampling = true;
        try {
            const m = Main.layoutManager.primaryMonitor;
            const shot = new Shell.Screenshot();
            const colors = [];
            for (const fy of [0.2, 0.5, 0.8]) {
                for (const fx of [0.15, 0.5, 0.85]) {
                    const [c] = await shot.pick_color(m.x + Math.round(m.width * fx),
                        m.y + Math.round(m.height * fy));
                    colors.push([c.red, c.green, c.blue]);
                }
            }
            this._ambient = colors;
        } catch (e) {
            this._ambient = [];
        } finally {
            this._sampling = false;
        }
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

    _rememberClip() {
        // password managers mark what they copy: never keep that
        const types = this._selection.get_mimetypes(Meta.SelectionType.SELECTION_CLIPBOARD);
        if (types.some(t => t.includes('passwordManagerHint') || t.includes('x-kde-passwordManagerHint')))
            return;
        St.Clipboard.get_default().get_text(St.ClipboardType.CLIPBOARD, (clip, text) => {
            if (!text || !text.trim() || text.length > 20000)
                return;
            this._clips = [{text, time: Date.now()}, ...this._clips.filter(c => c.text !== text)].slice(0, 50);
        });
    }

    ClipHistory() {
        return JSON.stringify(this._clips ?? []);
    }

    ClipClear() {
        this._clips = [];
        return 'ok';
    }

    // Keyboard language for the tablet's Persian keys: 'fa' = the Persian
    // layout, 'en' = the first layout that isn't Persian.
    SetInputSource(id) {
        const manager = Keyboard.getInputSourceManager();
        const sources = Object.values(manager.inputSources);
        const persian = s => s.type === 'xkb' && s.id.split('+')[0] === 'ir';
        const pick = sources.find(s => (id === 'fa' ? persian(s) : !persian(s)));
        if (pick && manager.currentSource !== pick)
            pick.activate(false);
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
