package app.pocketremote;

import android.content.Context;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import android.widget.EditText;

/** A text box that also reports a backspace pressed while it is empty, so it can be forwarded to the TV. */
final class MirrorEditText extends EditText {
    private Runnable onEmptyBackspace;
    private long lastEmpty;

    MirrorEditText(Context c) {
        super(c);
    }

    void setOnEmptyBackspace(Runnable r) {
        onEmptyBackspace = r;
    }

    private void emptyBackspace() {
        long now = System.currentTimeMillis();
        // some keyboards report one press both as a key event and as a deletion
        if (onEmptyBackspace == null || now - lastEmpty < 40) return;
        lastEmpty = now;
        onEmptyBackspace.run();
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        InputConnection ic = super.onCreateInputConnection(outAttrs);
        if (ic == null) return null;
        return new InputConnectionWrapper(ic, true) {
            @Override public boolean deleteSurroundingText(int before, int after) {
                if (before > 0 && length() == 0) emptyBackspace();
                return super.deleteSurroundingText(before, after);
            }

            @Override public boolean sendKeyEvent(KeyEvent ev) {
                if (ev.getAction() == KeyEvent.ACTION_DOWN && ev.getKeyCode() == KeyEvent.KEYCODE_DEL && length() == 0) {
                    emptyBackspace();
                }
                return super.sendKeyEvent(ev);
            }
        };
    }
}
