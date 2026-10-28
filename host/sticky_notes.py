#!/usr/bin/env python3
"""Fedora Pad sticky notes: the notes written on the tablet, as coloured cards
on the Fedora desktop. Updates by itself whenever the tablet changes them."""
import json
import os
import subprocess

import gi

gi.require_version("Gtk", "4.0")
from gi.repository import Gio, GLib, Gtk  # noqa: E402

NOTES_FILE = os.path.expanduser("~/.config/tabletdesk/notes.json")
NOTES_MD = os.path.expanduser("~/Documents/Fedora Pad Notes.md")
COLOURS = {"yellow": "#FFE58A", "pink": "#FFB3C7", "blue": "#A8D4FF",
           "green": "#B5EBA8", "purple": "#D9B8F5", "orange": "#FFCB8A"}

CSS = "".join(".note-%s { background: %s; }\n" % (k, v) for k, v in COLOURS.items()) + """
.note { border-radius: 14px; padding: 14px; color: #1d1d1f; box-shadow: 0 2px 8px rgba(0,0,0,.18); }
.note label { font-size: 15px; }
.empty { opacity: .6; font-size: 15px; }
"""


def load():
    try:
        with open(NOTES_FILE) as f:
            notes = json.load(f)
        return notes if isinstance(notes, list) else []
    except (OSError, ValueError):
        return []


class Notes(Gtk.Application):
    def __init__(self):
        super().__init__(application_id="io.github.aliihjt.FedoraPadNotes",
                         flags=Gio.ApplicationFlags.DEFAULT_FLAGS)

    def do_activate(self):
        if self.get_active_window():  # already open: just bring it forward
            self.get_active_window().present()
            return
        css = Gtk.CssProvider()
        css.load_from_data(CSS.encode())
        Gtk.StyleContext.add_provider_for_display(
            Gtk.Widget.get_display(Gtk.Label()), css, Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION)

        win = Gtk.ApplicationWindow(application=self, title="Fedora Pad Notes")
        win.set_default_size(420, 620)
        header = Gtk.HeaderBar()
        as_text = Gtk.Button(label="Open as text")
        as_text.connect("clicked", lambda *_: subprocess.Popen(["xdg-open", NOTES_MD]))
        header.pack_end(as_text)
        win.set_titlebar(header)

        self.box = Gtk.FlowBox(selection_mode=Gtk.SelectionMode.NONE, max_children_per_line=3,
                               column_spacing=12, row_spacing=12, homogeneous=False)
        for side in ("top", "bottom", "start", "end"):
            getattr(self.box, "set_margin_" + side)(14)
        scroll = Gtk.ScrolledWindow(hscrollbar_policy=Gtk.PolicyType.NEVER)
        scroll.set_child(self.box)
        win.set_child(scroll)

        os.makedirs(os.path.dirname(NOTES_FILE), exist_ok=True)
        self.monitor = Gio.File.new_for_path(NOTES_FILE).monitor_file(Gio.FileMonitorFlags.WATCH_MOVES, None)
        self.monitor.connect("changed", lambda *_: GLib.timeout_add(150, self.refresh))
        self.refresh()
        win.present()

    def refresh(self):
        while (child := self.box.get_first_child()) is not None:
            self.box.remove(child)
        notes = [n for n in load() if isinstance(n, dict) and str(n.get("text", "")).strip()]
        if not notes:
            empty = Gtk.Label(label="No notes yet.\nWrite one in Fedora Pad › Notes on the tablet.",
                              justify=Gtk.Justification.CENTER)
            empty.add_css_class("empty")
            self.box.append(empty)
        for n in notes:
            card = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
            card.add_css_class("note")
            colour = str(n.get("color", "yellow"))
            card.add_css_class("note-" + (colour if colour in COLOURS else "yellow"))
            text = Gtk.Label(label=str(n["text"]), wrap=True, xalign=0, selectable=True,
                             max_width_chars=28, width_chars=14)
            card.append(text)
            self.box.append(card)
        return GLib.SOURCE_REMOVE


if __name__ == "__main__":
    Notes().run(None)
