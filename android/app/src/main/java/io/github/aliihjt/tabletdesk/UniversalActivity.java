package io.github.aliihjt.tabletdesk;

import android.content.Intent;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Universal Control's page: is it ready, on or off, which side of the laptop the
 * tablet sits on, and the one-time step of turning the helper on in Android.
 */
public class UniversalActivity extends Page {
    private TextView status;
    private Switch onSwitch;
    private LinearLayout sideBox;
    private TextView allow;

    @Override
    protected String title() {
        return "Universal Control";
    }

    @Override
    protected void build() {
        content.addView(th.caption("Push the laptop's pointer past the edge of its screen and it moves on to this tablet: "
                + "the laptop's mouse, touchpad and keyboard then work here (tap, drag, two-finger scroll, typing). "
                + "Move back off the tablet's edge to return. Ctrl+Alt+Esc always brings them back. "
                + "Works over the cable and over Wi-Fi; not while the tablet is the laptop's second screen."));

        status = th.body("…");
        status.setPadding(th.dp(4), th.dp(14), th.dp(4), th.dp(4));
        content.addView(status);

        allow = th.primaryButton("Turn on the helper (once)", v -> {
            toastLong("Accessibility › Fedora Pad Universal Control › turn it on");
            startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS));
        });
        content.addView(allow, th.fullWidth(12));
        TextView why = th.caption("Android only lets an accessibility helper tap and type for you. "
                + "It acts only while the laptop's pointer is on the tablet, and reads nothing from the screen.");
        content.addView(why, th.fullWidth(6));

        onSwitch = th.toggle(true, on -> set("on", on));
        content.addView(th.group(th.row("↔️", Theme.INDIGO, "Universal Control", "Use the laptop's mouse and keyboard here",
                onSwitch)), th.fullWidth(20));

        content.addView(th.sectionHeader("Where the tablet is"));
        sideBox = new LinearLayout(this);
        content.addView(sideBox);
        paintSide("right");
        content.addView(th.caption("The pointer crosses at that edge of the laptop screen."), th.fullWidth(6));
    }

    private void paintSide(String side) {
        sideBox.removeAllViews();
        sideBox.addView(th.segmented(new String[][]{{"left", "◀ Left of the laptop"}, {"right", "Right of the laptop ▶"}},
                side, v -> set("side", v)), th.fullWidth(0));
    }

    private void set(String key, Object value) {
        try {
            link.send(new JSONObject().put("t", "ucset").put(key, value));
        } catch (JSONException ignored) {
        }
    }

    private void toastLong(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean helper = UniversalService.on();
        allow.setVisibility(helper ? android.view.View.GONE : android.view.View.VISIBLE);
        show(null);
        link.on("ucstate", this::show);
        link.ask("uc");
    }

    @Override
    protected void onPause() {
        super.onPause();
        link.on("ucstate", null);
    }

    private void show(JSONObject st) {
        boolean helper = UniversalService.on();
        String text;
        if (!link.isConnected()) text = "○ Not connected to the laptop";
        else if (!helper) text = "○ One step left: turn on the helper below";
        else if (st == null) text = "…";
        else if (!st.optBoolean("on", true)) text = "○ Off";
        else text = "● Ready: push the pointer past the " + st.optString("side", "right") + " edge of the laptop screen";
        status.setText(text);
        if (st != null) {
            onSwitch.setOnCheckedChangeListener(null);
            onSwitch.setChecked(st.optBoolean("on", true));
            onSwitch.setOnCheckedChangeListener((v, on) -> set("on", on));
            paintSide(st.optString("side", "right"));
        }
    }
}
