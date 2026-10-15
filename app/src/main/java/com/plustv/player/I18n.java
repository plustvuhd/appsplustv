package com.plustv.player;

import android.content.Context;
import android.content.SharedPreferences;
import android.widget.Toast;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Idiomas do app nativo: English, Português (Brasil), Português (Portugal), Español.
 * Os textos do código ficam em português (BR); I18n.t() troca pelo idioma escolhido.
 * Primeira abertura: país do aparelho (e do IP, via painel /api/public/lang) + idioma do aparelho.
 * Em Ajustes → Idioma o cliente escolhe à mão (ou volta ao Automático).
 */
public final class I18n {
    private I18n() {}

    public static final String[] CODES = {"auto", "en", "pt-BR", "pt-PT", "es"};
    public static final String[] LABELS = {"Automático (pelo país)", "English", "Português (Brasil)", "Português (Portugal)", "Español"};

    private static Context app;
    private static String lang = "pt-BR";
    private static boolean ready;
    private static final Map<String, String[]> M = new HashMap<>(); // pt-BR → {en, es, ptPT?}

    private static SharedPreferences sp() { return app.getSharedPreferences("plustv", Context.MODE_PRIVATE); }
    public static String lang() { return lang; }
    public static boolean manual() { return ready && sp().getBoolean("lang_manual", false); }
    public static String label(String code) { for (int i = 0; i < CODES.length; i++) if (CODES[i].equals(code)) return LABELS[i]; return code; }

    /** Chamar no onCreate de cada tela, antes de montar a interface. */
    public static void init(Context c) {
        if (ready || c == null) return;
        app = c.getApplicationContext();
        ready = true;
        build();
        String saved = sp().getString("lang", "");
        if (saved.isEmpty()) { lang = fromDevice(); sp().edit().putString("lang", lang).apply(); }
        else lang = saved;
        geo(false);
    }

    private static String fromDevice() {
        Locale l = Locale.getDefault();
        String code = l.getLanguage() == null ? "" : l.getLanguage().toLowerCase(Locale.ROOT);
        String cc = l.getCountry() == null ? "" : l.getCountry().toUpperCase(Locale.ROOT);
        if (code.equals("pt")) return cc.equals("PT") ? "pt-PT" : "pt-BR";
        if (code.equals("en")) return "en";
        if (code.equals("es")) return "es";
        return code.isEmpty() ? "pt-BR" : "en";
    }

    static String fromCountry(String cc) {
        if (cc == null) return null;
        cc = cc.toUpperCase(Locale.ROOT);
        if (cc.equals("BR")) return "pt-BR";
        if ("PT AO MZ CV GW ST TL".contains(cc) && cc.length() == 2) return "pt-PT";
        if (cc.length() == 2 && "ES MX AR CO CL PE VE EC GT CU BO DO HN PY SV NI CR PA UY PR GQ".contains(cc)) return "es";
        if (cc.length() == 2 && "US GB IE CA AU NZ ZA IN SG PH NG KE GH JM TT BS BB".contains(cc)) return "en";
        return null;
    }

    /** País do IP (a cada 12 h, só se o cliente não escolheu à mão). */
    private static void geo(boolean force) {
        if (manual()) return;
        long at = sp().getLong("lang_geo", 0);
        if (!force && at > 0 && System.currentTimeMillis() - at < 12L * 3600_000L) return;
        new Thread(() -> {
            try {
                String panel = sp().getString("panel", "https://konnex.business");
                java.net.HttpURLConnection h = (java.net.HttpURLConnection) new java.net.URL(panel + "/api/public/lang").openConnection();
                h.setConnectTimeout(5000); h.setReadTimeout(5000);
                if (h.getResponseCode() != 200) return;
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                java.io.InputStream in = h.getInputStream(); byte[] b = new byte[2048]; int n;
                while ((n = in.read(b)) > 0) bo.write(b, 0, n);
                String cc = new org.json.JSONObject(bo.toString("UTF-8")).optString("country", "");
                if (manual()) return;
                String l = fromCountry(cc);
                if (l == null) l = fromDevice();
                sp().edit().putLong("lang_geo", System.currentTimeMillis()).putString("lang", l).apply();
                lang = l; // vale nas próximas telas abertas
            } catch (Throwable ignore) {}
        }).start();
    }

    /** code = "auto" volta a seguir o país. */
    public static void set(String code) {
        if (!ready) return;
        if ("auto".equals(code)) { sp().edit().putBoolean("lang_manual", false).putLong("lang_geo", 0).putString("lang", fromDevice()).apply(); lang = fromDevice(); geo(true); }
        else { sp().edit().putBoolean("lang_manual", true).putString("lang", code).apply(); lang = code; }
    }

    // ---------- tradução ----------
    public static CharSequence t(CharSequence s) { return s == null ? null : t(s.toString()); }

    public static String t(String s) {
        if (s == null || s.isEmpty() || !ready || "pt-BR".equals(lang)) return s;
        String key = s.trim();
        String[] r = M.get(key);
        if (r != null) { int i = lang.equals("en") ? 0 : lang.equals("es") ? 1 : 2; String v = r[i]; if (v == null) v = i == 2 ? pt(key) : key; return s.replace(key, v); }
        // contagens ("12 canais")
        java.util.regex.Matcher mm = COUNT.matcher(key);
        if (mm.matches()) {
            String w = mm.group(2);
            String[] cw = w.equals("favoritos") ? new String[]{"favorites", "favoritos"} : w.equals("canais") ? new String[]{"channels", "canales"} : w.equals("títulos") ? new String[]{"titles", "títulos"} : new String[]{"results", "resultados"};
            return mm.group(1) + " " + (lang.equals("en") ? cw[0] : lang.equals("es") ? cw[1] : w);
        }
        // texto montado em código ("Usuário: x | Vencimento: y"): só mexe se começa por um pedaço conhecido
        boolean head = false;
        for (String[] p : PARTS) if (s.startsWith(p[0])) { head = true; break; }
        if (!head) return s;
        String o = s;
        for (String[] p : PARTS) {
            if (!o.contains(p[0])) continue;
            String v = lang.equals("en") ? p[1] : lang.equals("es") ? p[2] : pt(p[0]);
            o = o.replace(p[0], v);
        }
        return o;
    }

    private static final java.util.regex.Pattern COUNT = java.util.regex.Pattern.compile("^(\\d+) (favoritos|canais|títulos|resultados)$");

    private static String pt(String s) {
        return s.replace("Carregando", "A carregar").replace("Usuário", "Utilizador").replace("usuário", "utilizador").replace("Senha", "Palavra-passe")
            .replace("tela cheia", "ecrã inteiro").replace("Tela cheia", "Ecrã inteiro").replace("aparelho", "dispositivo").replace("Aparelho", "Dispositivo")
            .replace("Salvar", "Guardar").replace("Buscar", "Pesquisar").replace("Buscando", "A pesquisar").replace("Assistir", "Ver").replace("Celular", "Telemóvel")
            .replace("Sintonizando", "A sintonizar").replace("Entrando", "A entrar");
    }

    public static Toast mk(Context c, CharSequence text, int dur) { return Toast.makeText(c, t(text), dur); }

    private static void a(String ptbr, String en, String es) { M.put(ptbr, new String[]{en, es, null}); }
    private static void a(String ptbr, String en, String es, String ptpt) { M.put(ptbr, new String[]{en, es, ptpt}); }

    private static final String[][] PARTS = {
        {"Idioma:", "Language:", "Idioma:"},
        {"Vencimento: ", "Due date: ", "Vencimiento: "},
        {"Usuário: ", "Username: ", "Usuario: "},
        {"Formato do fluxo:", "Stream format:", "Formato de transmisión:"},
        {"Player:", "Player:", "Reproductor:"},
        {"Pastas adultas:", "Adult folders:", "Carpetas para adultos:"},
        {"TS (recomendado)", "TS (recommended)", "TS (recomendado)"},
        {"mostrar (travadas com PIN)", "show (locked with PIN)", "mostrar (bloqueadas con PIN)"},
        {"ocultas", "hidden", "ocultas"},
        {"Automático (VLC na TV/box)", "Automatic (VLC on TV/box)", "Automático (VLC en TV/box)"},
        {"Automático", "Automatic", "Automático"},
        {"Continuar (", "Continue (", "Continuar ("},
        {"Continuar: T", "Continue: S", "Continuar: T"},
        {"Assistir T1 E1", "Watch S1 E1", "Ver T1 E1"},
        {"Assistir", "Watch", "Ver"},
        {"Temporada ", "Season ", "Temporada "},
        {"Agora: ", "Now: ", "Ahora: "},
        {"A seguir: ", "Next: ", "A continuación: "},
        {"Proporção: Ajustar", "Aspect: Fit", "Proporción: Ajustar"},
        {"Proporção: Preencher", "Aspect: Fill", "Proporción: Rellenar"},
        {"Proporção: Esticar", "Aspect: Stretch", "Proporción: Estirar"},
        {"Seu plano permite ", "Your plan allows ", "Su plan permite "},
        {" tela(s) ao mesmo tempo e ", " screen(s) at the same time and ", " pantalla(s) al mismo tiempo y "},
        {" já está assistindo. Quer desconectar o outro e assistir aqui?", " is already watching. Disconnect the other one and watch here?", " ya está viendo. ¿Quiere desconectar el otro y ver aquí?"},
        {"Não foi possível entrar (erro ", "Could not log in (error ", "No fue posible entrar (error "},
        {"Sem conexão\nNão consegui abrir o ", "No connection\nCould not open ", "Sin conexión\nNo pude abrir "},
        {". Verifique a internet.", ". Check your internet.", ". Compruebe su internet."}
    };

    private static void build() {
        a("Canais", "Channels", "Canales"); a("Filmes", "Movies", "Películas"); a("Séries", "Series", "Series");
        a("Ajustes", "Settings", "Ajustes", "Definições"); a("Favoritos", "Favorites", "Favoritos"); a("Recentes", "Recent", "Recientes");
        a("Todos", "All", "Todos"); a("Todos os canais", "All channels", "Todos los canales"); a("★ Favoritos", "★ Favorites", "★ Favoritos");
        a("Recentemente adicionados", "Recently added", "Añadidos recientemente"); a("Mais recentes primeiro", "Newest first", "Más recientes primero"); a("Nome", "Name", "Nombre");
        a("Nenhum favorito aqui. Segure OK em um item para favoritar.", "No favorites here. Hold OK on an item to favorite it.", "Aún no hay favoritos aquí. Mantenga OK sobre un elemento para marcarlo.");
        a("Nenhum favorito ainda. Segure OK em um canal para favoritar.", "No favorites yet. Hold OK on a channel to favorite it.", "Aún no hay favoritos. Mantenga OK sobre un canal para marcarlo.");
        a("Nenhum favorito ainda. Segure OK em uma capa para favoritar.", "No favorites yet. Hold OK on a cover to favorite it.", "Aún no hay favoritos. Mantenga OK sobre una portada para marcarlo.");
        a("Nenhum canal aqui.", "No channels here.", "No hay canales aquí."); a("Nada encontrado", "Nothing found", "No se encontró nada");
        a("Buscar canal", "Search channel", "Buscar canal", "Pesquisar canal"); a("Buscar", "Search", "Buscar", "Pesquisar"); a("Buscando…", "Searching…", "Buscando…", "A pesquisar…");
        a("Buscar filme", "Search movie", "Buscar película", "Pesquisar filme"); a("Buscar série", "Search series", "Buscar serie", "Pesquisar série");
        a("Buscar filme em todas as pastas", "Search movie in all folders", "Buscar película en todas las carpetas", "Pesquisar filme em todas as pastas");
        a("Buscar série em todas as pastas", "Search series in all folders", "Buscar serie en todas las carpetas", "Pesquisar série em todas as pastas");
        a("Nome do canal", "Channel name", "Nombre del canal"); a("Escolha um canal", "Choose a channel", "Elija un canal");
        a("OK: tela cheia   |   Segure OK ou Menu: favoritar", "OK: full screen   |   Hold OK or Menu: favorite", "OK: pantalla completa   |   Mantenga OK o Menú: favorito", "OK: ecrã inteiro   |   Mantenha OK ou Menu: favorito");
        a("Carregando categorias…", "Loading categories…", "Cargando categorías…"); a("Carregando…", "Loading…", "Cargando…"); a("Carregando...", "Loading...", "Cargando...");
        a("Não foi possível carregar os canais. Confira a internet e tente de novo.", "Could not load the channels. Check your internet and try again.", "No fue posible cargar los canales. Compruebe su internet e inténtelo de nuevo.");
        a("Não foi possível carregar esta pasta.", "Could not load this folder.", "No fue posible cargar esta carpeta.");
        a("Não foi possível buscar agora.", "Could not search right now.", "No fue posible buscar ahora.");
        a("Não foi possível carregar. Confira a internet.", "Could not load. Check your internet.", "No fue posible cargar. Compruebe su internet.");
        a("Não foi possível carregar. Tente de novo.", "Could not load. Try again.", "No fue posible cargar. Inténtelo de nuevo.");
        a("Pasta travada. Aperte OK e digite o PIN.", "Folder locked. Press OK and enter the PIN.", "Carpeta bloqueada. Pulse OK e ingrese el PIN.");
        a("Pasta travada", "Folder locked", "Carpeta bloqueada"); a("Ocultar esta pasta", "Hide this folder", "Ocultar esta carpeta");
        a("Tirar a trava de PIN", "Remove PIN lock", "Quitar el bloqueo con PIN"); a("Travar com PIN", "Lock with PIN", "Bloquear con PIN"); a("PIN para ocultar", "PIN to hide", "PIN para ocultar");
        a("Sintonizando…", "Tuning…", "Sintonizando…", "A sintonizar…"); a("Sintonizando", "Tuning", "Sintonizando", "A sintonizar");
        a("Vídeo H.265: aperte OK para ver em tela cheia (VLC).", "H.265 video: press OK to watch full screen (VLC).", "Vídeo H.265: pulse OK para ver en pantalla completa (VLC).", "Vídeo H.265: prima OK para ver em ecrã inteiro (VLC).");
        a("Não abriu aqui. Aperte OK para tela cheia.", "Did not open here. Press OK for full screen.", "No se abrió aquí. Pulse OK para pantalla completa.", "Não abriu aqui. Prima OK para ecrã inteiro.");
        a("Vídeo H.265: trocando para o VLC…", "H.265 video: switching to VLC…", "Vídeo H.265: cambiando a VLC…", "Vídeo H.265: a mudar para o VLC…");
        a("Canal pesado: aumentando o buffer…", "Heavy channel: increasing the buffer…", "Canal pesado: aumentando el búfer…", "Canal pesado: a aumentar o buffer…");
        a("Canal FHD: ajustando o buffer…", "FHD channel: adjusting the buffer…", "Canal FHD: ajustando el búfer…", "Canal FHD: a ajustar o buffer…");
        a("Pular abertura", "Skip intro", "Saltar intro", "Saltar abertura"); a("Pular abertura  (OK)", "Skip intro  (OK)", "Saltar intro  (OK)", "Saltar abertura  (OK)");
        a("AO VIVO", "LIVE", "EN VIVO"); a("Cancelar", "Cancel", "Cancelar"); a("Salvar", "Save", "Guardar"); a("OK", "OK", "OK");
        a("Sem conexão. Confira a internet e tente de novo.", "No connection. Check your internet and try again.", "Sin conexión. Compruebe su internet e inténtelo de nuevo.");
        a("Tentar de novo", "Try again", "Intentar de nuevo"); a("Sair", "Exit", "Salir", "Sair");
        a("Do início", "From the start", "Desde el principio"); a("☆ Favoritar", "☆ Favorite", "☆ Favorito"); a("★ Nos favoritos", "★ In favorites", "★ En favoritos");
        a("Sem sinopse.", "No synopsis.", "Sin sinopsis."); a("Não foi possível carregar os detalhes, mas você pode assistir.", "Could not load the details, but you can watch.", "No fue posible cargar los detalles, pero puede ver.", "Não foi possível carregar os detalhes, mas pode ver.");
        a("Aguarde carregar os episódios", "Wait for the episodes to load", "Espere a que carguen los episodios"); a("Não foi possível reproduzir agora. Tente de novo.", "Could not play right now. Try again.", "No fue posible reproducir ahora. Inténtelo de nuevo.");
        a("PIN incorreto", "Incorrect PIN", "PIN incorrecto"); a("Adicionado aos favoritos", "Added to favorites", "Añadido a favoritos"); a("Removido dos favoritos", "Removed from favorites", "Eliminado de favoritos");
        a("Aperte voltar de novo para sair", "Press back again to exit", "Pulse atrás otra vez para salir", "Prima voltar novamente para sair");
        a("Digite seu usuário e senha", "Enter your username and password", "Ingrese su usuario y contraseña", "Introduza o seu utilizador e palavra-passe");
        a("Código do provedor (se tiver)", "Provider code (if you have one)", "Código del proveedor (si tiene)"); a("Usuário", "Username", "Usuario"); a("Senha", "Password", "Contraseña");
        a("Entrar", "Log in", "Entrar", "Iniciar sessão"); a("Entrando…", "Logging in…", "Entrando…", "A entrar…"); a("Usar a versão web", "Use the web version", "Usar la versión web");
        a("Informe usuário e senha.", "Enter username and password.", "Ingrese usuario y contraseña."); a("Informe o usuário e a senha.", "Enter the username and password.", "Ingrese el usuario y la contraseña.");
        a("Outro aparelho assumiu a sua tela.", "Another device took over your screen.", "Otro dispositivo ocupó su pantalla."); a("Aparelho bloqueado.", "Device blocked.", "Dispositivo bloqueado.");
        a("Limite de telas atingido", "Screen limit reached", "Límite de pantallas alcanzado", "Limite de ecrãs atingido"); a("Assistir aqui", "Watch here", "Ver aquí", "Ver aqui"); a("Voltar", "Back", "Volver");
        a("Reabra Canais/Filmes/Séries para atualizar as pastas", "Reopen Channels/Movies/Series to update the folders", "Vuelva a abrir Canales/Películas/Series para actualizar las carpetas");
        a("Alterar PIN das pastas (padrão 0000)", "Change the folder PIN (default 0000)", "Cambiar el PIN de las carpetas (predeterminado 0000)"); a("PIN atual", "Current PIN", "PIN actual");
        a("Novo PIN (4 a 8 dígitos)", "New PIN (4 to 8 digits)", "Nuevo PIN (4 a 8 dígitos)"); a("Novo PIN", "New PIN", "Nuevo PIN"); a("PIN", "PIN", "PIN");
        a("Use de 4 a 8 dígitos", "Use 4 to 8 digits", "Use de 4 a 8 dígitos"); a("PIN alterado", "PIN changed", "PIN cambiado");
        a("Mostrar de novo as pastas ocultas e tirar travas", "Show hidden folders again and remove locks", "Mostrar de nuevo las carpetas ocultas y quitar bloqueos");
        a("Pronto. Reabra Canais/Filmes/Séries.", "Done. Reopen Channels/Movies/Series.", "Listo. Vuelva a abrir Canales/Películas/Series.");
        a("Sair desta conta", "Log out of this account", "Cerrar sesión de esta cuenta", "Terminar sessão desta conta"); a("Versão do app: 2.4 (nativo)", "App version: 2.4 (native)", "Versión de la app: 2.4 (nativa)", "Versão da aplicação: 2.4 (nativa)");
        a("Idioma", "Language", "Idioma"); a("Idioma:  ", "Language:  ", "Idioma:  ");
        a("HLS (m3u8)", "HLS (m3u8)", "HLS (m3u8)"); a("Fire Stick", "Fire Stick", "Fire Stick"); a("Tablet", "Tablet", "Tablet"); a("Celular", "Phone", "Móvil", "Telemóvel"); a("TV box", "TV box", "TV box");
    }
}
