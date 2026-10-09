package com.plustv.player;

import android.view.View;

/** Uma "aba" da tela principal (Canais, Filmes, Séries, Favoritos, Ajustes). */
public abstract class Screen {
    protected final NHome home;
    protected final Net.Sess s;
    protected Screen(NHome home) { this.home = home; this.s = home.sess; }
    public abstract View view();
    /** Chamado quando a aba aparece. */
    public void onShow() {}
    /** Chamado quando sai da aba ou a tela fecha (pare players e tarefas). */
    public void onHide() {}
    /** Tecla de favoritar/menu. */
    public boolean onMenu() { return false; }
    /** Voltar: devolve true se tratou. */
    public boolean onBack() { return false; }
    /** Primeiro elemento que recebe o cursor. */
    public View firstFocus() { return view(); }
}
