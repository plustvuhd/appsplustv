package com.plustv.player;

import android.content.Context;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

/** Views que traduzem sozinhas o texto/dica para o idioma escolhido (ver I18n). */
public final class Tr {
    private Tr() {}

    public static class TV extends TextView {
        public TV(Context c) { super(c); }
        @Override public void setText(CharSequence text, BufferType type) { super.setText(I18n.t(text), type); }
        @Override public void setHint(CharSequence hint) { super.setHint(I18n.t(hint)); }
    }

    public static class BTN extends Button {
        public BTN(Context c) { super(c); }
        @Override public void setText(CharSequence text, BufferType type) { super.setText(I18n.t(text), type); }
    }

    public static class ET extends EditText {
        public ET(Context c) { super(c); }
        @Override public void setHint(CharSequence hint) { super.setHint(I18n.t(hint)); }
    }
}
