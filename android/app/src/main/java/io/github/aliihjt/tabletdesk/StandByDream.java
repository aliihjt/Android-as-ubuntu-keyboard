package io.github.aliihjt.tabletdesk;

import android.service.dreams.DreamService;

/**
 * StandBy as Android's screen saver: Settings › Display › Screen saver ›
 * "Fedora Pad StandBy", shown while the tablet charges or is docked.
 */
public class StandByDream extends DreamService {
    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(true);  // the music buttons work; Back or the power key wakes it
        setFullscreen(true);
        setScreenBright(true);
        setContentView(new StandByView(this));
    }
}
