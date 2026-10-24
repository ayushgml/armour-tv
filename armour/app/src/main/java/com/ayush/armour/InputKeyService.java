package com.ayush.armour;

import android.accessibilityservice.AccessibilityService;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * The remote's Input button (KEYCODE_TV, or TV_INPUT) opens Armour's own Inputs screen from any app.
 * Only those two keys are consumed; every other key passes through untouched.
 */
public final class InputKeyService extends AccessibilityService {
    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (code != KeyEvent.KEYCODE_TV && code != KeyEvent.KEYCODE_TV_INPUT) return false;
        if (event.getAction() == KeyEvent.ACTION_UP) Commander.goHome(this, "inputs");
        return true;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }
}
