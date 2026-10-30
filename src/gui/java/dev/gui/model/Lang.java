package dev.gui.model;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;

/// The words of Genies: every text the window shows, in one language. A value, as [Palette] is,
/// so that Genies can be shown in another language as it can in other colours.
///
/// The texts are in a record for each part of the window, as whoever words a part finds its
/// texts together. A part knows the language it speaks, and each of its texts is a method named
/// after what it says in English, such as `YOUR_GENIES()`, which says it in that language. A text
/// assembled from something else, such as a count, takes that as its parameters, as languages
/// differ in how they assemble one.
///
/// A language is added as a constant here. Every text then switches over it without saying it in
/// that language yet, and the compiler lists each of them, until all are.
public enum Lang {
    ENGLISH,
    GERMAN,
    SPANISH,
    JAPANESE,
    CHINESE;

    /// The language's name, as it calls itself, so that whoever speaks it finds it in any other.
    public String NAME_IN_ITSELF() {
        return switch (this) {
            case ENGLISH -> "English";
            case GERMAN -> "Deutsch";
            case SPANISH -> "Español";
            case JAPANESE -> "日本語";
            case CHINESE -> "中文";
        };
    }

    /// The words of the window around the genies: the list of them, and what went wrong.
    public Window WINDOW() {
        return new Window(this);
    }

    /// The words of the header above a genie's pages, and what its lamp asks the user to allow.
    public Header HEADER() {
        return new Header(this);
    }

    /// The words of a genie's desktop, beside its chat.
    public Desktop DESKTOP() {
        return new Desktop(this);
    }

    /// The words of a genie's chat: the conversation, and the composer under it.
    public Chat CHAT() {
        return new Chat(this);
    }

    /// The words of the dialogs the window asks and tells the user things in.
    public Dialogs DIALOGS() {
        return new Dialogs(this);
    }

    /// The words of what can be done with a genie as a whole.
    public ActionsPage ACTIONS_PAGE() {
        return new ActionsPage(this);
    }

    /// The words of a genie's home, and linking its folders into the user's.
    public HomeFolders HOME_FOLDERS() {
        return new HomeFolders(this);
    }

    /// The words of whether a genie may reach this computer, and its rules.
    public HostAccessPage HOST_ACCESS_PAGE() {
        return new HostAccessPage(this);
    }

    /// The words of the settings every genie shares, and the welcome above them.
    public SettingsPage SETTINGS_PAGE() {
        return new SettingsPage(this);
    }

    /// The words of when jobs wake a genie, and what they did.
    public SchedulePage SCHEDULE_PAGE() {
        return new SchedulePage(this);
    }

    /// The words of the moments a genie's home was saved at, and going back to one.
    public HistoryPage HISTORY_PAGE() {
        return new HistoryPage(this);
    }

    /// The words of the parts of the window that several pages share.
    public Parts PARTS() {
        return new Parts(this);
    }

    /// The words of days and spans of time, as the pages say them.
    public Dates DATES() {
        return new Dates(this);
    }

    /// Names as an English sentence lists them: "main", "main and docs", "a, b and c".
    private static String listedInEnglish(List<String> names) {
        return joined(names, ", ", " and ");
    }

    /// The same, as choices: "a, b or c".
    private static String eitherInEnglish(List<String> names) {
        return joined(names, ", ", " or ");
    }

    /// Names as a German sentence lists them: "main", "main und docs", "a, b und c".
    private static String listedInGerman(List<String> names) {
        return joined(names, ", ", " und ");
    }

    /// The same, as choices: "a, b oder c".
    private static String eitherInGerman(List<String> names) {
        return joined(names, ", ", " oder ");
    }

    /// Names as a Spanish sentence lists them: "main", "main y docs", "a, b y c".
    private static String listedInSpanish(List<String> names) {
        return joined(names, ", ", " y ");
    }

    /// The same, as choices: "a, b o c".
    private static String eitherInSpanish(List<String> names) {
        return joined(names, ", ", " o ");
    }

    /// Names as a Japanese sentence lists them: "a", "aとb", "a、b、c".
    private static String listedInJapanese(List<String> names) {
        return joined(names, "、", names.size() == 2 ? "と" : "、");
    }

    /// The same, as choices: "a、b、またはc".
    private static String eitherInJapanese(List<String> names) {
        return joined(names, "、", "、または");
    }

    /// Names as a Chinese sentence lists them: "a", "a和b", "a、b和c".
    private static String listedInChinese(List<String> names) {
        return joined(names, "、", "和");
    }

    /// The same, as choices: "a、b或c".
    private static String eitherInChinese(List<String> names) {
        return joined(names, "、", "或");
    }

    /// Names joined by `between`, the last two by `beforeTheLast`.
    private static String joined(List<String> names, String between, String beforeTheLast) {
        return names.size() < 2
            ? String.join("", names)
            : String.join(between, names.subList(0, names.size() - 1)) + beforeTheLast + names.getLast();
    }

    /// A time of day as Spanish says it after "a" or "desde": "las 09:00", but "la 01:00".
    private static String hourInSpanish(String time) {
        return (time.startsWith("01:") ? "la " : "las ") + time;
    }

    /// The words of the window around the genies: the list of them, and what went wrong.
    public record Window(Lang lang) {
        public String NEW_GENIE() {
            return switch (lang) {
                case ENGLISH -> "New genie";
                case GERMAN -> "Neuer Dschinn";
                case SPANISH -> "Nuevo genio";
                case JAPANESE -> "新しいジーニー";
                case CHINESE -> "新建精灵";
            };
        }

        public String A_NEW_GENIE_WITH_A_SANDBOXED_DESKTOP_OF_ITS_OWN() {
            return switch (lang) {
                case ENGLISH -> "A new genie, with a sandboxed desktop of its own";
                case GERMAN -> "Ein neuer Dschinn mit einem eigenen Desktop in einer Sandbox";
                case SPANISH -> "Un genio nuevo, con su propio escritorio aislado";
                case JAPANESE -> "専用のデスクトップをサンドボックス内に持つ、新しいジーニー";
                case CHINESE -> "新的精灵，拥有自己的沙箱桌面";
            };
        }

        public String SETTINGS() {
            return switch (lang) {
                case ENGLISH -> "Settings";
                case GERMAN -> "Einstellungen";
                case SPANISH -> "Ajustes";
                case JAPANESE -> "設定";
                case CHINESE -> "设置";
            };
        }

        public String SETTINGS_THE_MODEL_EVERY_GENIE_USES() {
            return switch (lang) {
                case ENGLISH -> "Settings: the model every genie uses. Pressed again, back to the genie";
                case GERMAN -> "Einstellungen: das Modell, das alle Dschinns nutzen. Ein zweiter Klick führt zurück zum Dschinn";
                case SPANISH -> "Ajustes: el modelo que usan todos los genios. Púlsalo otra vez para volver al genio";
                case JAPANESE -> "設定：すべてのジーニーが使うモデル。もう一度押すとジーニーに戻ります";
                case CHINESE -> "设置：所有精灵使用的模型。再按一次即可回到精灵";
            };
        }

        public String YOUR_GENIES() {
            return switch (lang) {
                case ENGLISH -> "YOUR GENIES";
                case GERMAN -> "DEINE DSCHINNS";
                case SPANISH -> "TUS GENIOS";
                case JAPANESE -> "ジーニー";
                case CHINESE -> "我的精灵";
            };
        }

        public String DRAG_TO_SEE_MORE_OF_YOUR_GENIES() {
            return switch (lang) {
                case ENGLISH -> "Drag to see more of your genies, or up to fold them away";
                case GERMAN -> "Ziehen, um mehr Dschinns zu sehen, oder ganz nach oben, um sie einzuklappen";
                case SPANISH -> "Arrastra para ver más genios, o hasta arriba para plegarlos";
                case JAPANESE -> "ドラッグするとジーニーをもっと表示します。上端までドラッグすると折りたたみます";
                case CHINESE -> "拖动以显示更多精灵，拖到顶部可将其收起";
            };
        }

        public String ADD_A_LAMP_FROM_ELSEWHERE() {
            return switch (lang) {
                case ENGLISH -> "Add a lamp from elsewhere…";
                case GERMAN -> "Lampe von anderswo hinzufügen…";
                case SPANISH -> "Añadir una lámpara de otro sitio…";
                case JAPANESE -> "ほかの場所のランプを追加…";
                case CHINESE -> "添加其他位置的神灯…";
            };
        }

        public String SHOW_A_LAMP_YOU_MADE_WITH_OILLAMP_AT_HERE_TOO() {
            return switch (lang) {
                case ENGLISH -> "Show a lamp you made with `oillamp at` here too. Genies finds those that are lit by itself";
                case GERMAN -> "Eine Lampe, die du mit `oillamp at` angelegt hast, auch hier zeigen. Brennende Lampen findet Genies von selbst";
                case SPANISH -> "Muestra aquí también una lámpara que creaste con `oillamp at`. Genies encuentra solo las que están encendidas";
                case JAPANESE -> "`oillamp at` で作ったランプをここにも表示します。点灯中のランプは Genies が自動で見つけます";
                case CHINESE -> "把你用 `oillamp at` 创建的神灯也显示在这里。已点亮的神灯 Genies 会自动找到";
            };
        }

        public String THINGS_WENT_WRONG(int count) {
            return switch (lang) {
                case ENGLISH -> count == 1 ? "Something went wrong" : count + " things went wrong";
                case GERMAN -> count == 1 ? "Etwas ist schiefgelaufen" : count + " Dinge sind schiefgelaufen";
                case SPANISH -> count == 1 ? "Algo salió mal" : count + " cosas salieron mal";
                case JAPANESE -> count == 1 ? "問題が発生しました" : count + " 件の問題が発生しました";
                case CHINESE -> count == 1 ? "出了点问题" : "出了 " + count + " 个问题";
            };
        }

        public String GENIES_CARRIED_ON_CLICK_TO_SEE_WHAT_HAPPENED() {
            return switch (lang) {
                case ENGLISH -> "Genies carried on. Click to see what happened";
                case GERMAN -> "Genies läuft weiter. Klicken, um zu sehen, was passiert ist";
                case SPANISH -> "Genies sigue funcionando. Haz clic para ver qué pasó";
                case JAPANESE -> "Genies は動作を続けています。クリックすると何が起きたかを表示します";
                case CHINESE -> "Genies 仍在正常运行。点击查看发生了什么";
            };
        }

        public String LAMPS_FROM_ELSEWHERE() {
            return switch (lang) {
                case ENGLISH -> "LAMPS FROM ELSEWHERE";
                case GERMAN -> "LAMPEN VON ANDERSWO";
                case SPANISH -> "LÁMPARAS DE OTROS SITIOS";
                case JAPANESE -> "ほかの場所のランプ";
                case CHINESE -> "其他位置的神灯";
            };
        }

        public String LAMPS_SOMEONE_LIT_WITH_OILLAMP_AT_OUTSIDE_GENIES() {
            return switch (lang) {
                case ENGLISH -> "Lamps someone lit with `oillamp at`, outside Genies. Genies leaves them as they are";
                case GERMAN -> "Lampen, die jemand außerhalb von Genies mit `oillamp at` entzündet hat. Genies lässt sie, wie sie sind";
                case SPANISH -> "Lámparas que alguien encendió con `oillamp at`, fuera de Genies. Genies las deja tal como están";
                case JAPANESE -> "Genies の外で `oillamp at` によって点灯されたランプです。Genies はそのままにしておきます";
                case CHINESE -> "有人在 Genies 之外用 `oillamp at` 点亮的神灯。Genies 不会改动它们";
            };
        }

        public String GENIE_STATUS_AND_WHAT_IT_SHOWS_RIGHT_CLICK_FOR_MORE(String name, String status, String showing) {
            return switch (lang) {
                case ENGLISH -> name + " — " + status + (showing.isEmpty() ? "" : ". Shows you: " + showing) + ". Right-click for more.";
                case GERMAN -> name + " — " + status + (showing.isEmpty() ? "" : ". Zeigt dir: " + showing) + ". Rechtsklick für mehr.";
                case SPANISH -> name + " — " + status + (showing.isEmpty() ? "" : ". Te muestra: " + showing) + ". Clic derecho para más opciones.";
                case JAPANESE -> name + " — " + status + (showing.isEmpty() ? "" : "。表示中：" + showing) + "。右クリックでその他の操作ができます。";
                case CHINESE -> name + " — " + status + (showing.isEmpty() ? "" : "。正在展示：" + showing) + "。右键单击可进行更多操作。";
            };
        }

        public String ASKS_YOU_TO_ALLOW_SOMETHING() {
            return switch (lang) {
                case ENGLISH -> "asks you to allow something";
                case GERMAN -> "bittet dich um eine Erlaubnis";
                case SPANISH -> "te pide permiso para algo";
                case JAPANESE -> "許可を求めています";
                case CHINESE -> "正在请求你的许可";
            };
        }

        public String MAY_REACH_THIS_COMPUTER() {
            return switch (lang) {
                case ENGLISH -> "may reach this computer";
                case GERMAN -> "darf auf diesen Rechner zugreifen";
                case SPANISH -> "puede acceder a este equipo";
                case JAPANESE -> "このコンピューターにアクセスできます";
                case CHINESE -> "可以访问这台电脑";
            };
        }

        public String SHOWS_YOU(String showing) {
            return switch (lang) {
                case ENGLISH -> "shows you: " + showing;
                case GERMAN -> "zeigt dir: " + showing;
                case SPANISH -> "te muestra: " + showing;
                case JAPANESE -> "表示中：" + showing;
                case CHINESE -> "正在展示：" + showing;
            };
        }

        public String SHOW_THIS_GENIES_CONVERSATIONS() {
            return switch (lang) {
                case ENGLISH -> "Show this genie's conversations";
                case GERMAN -> "Die Gespräche dieses Dschinns zeigen";
                case SPANISH -> "Mostrar las conversaciones de este genio";
                case JAPANESE -> "このジーニーとの会話を表示";
                case CHINESE -> "显示与此精灵的对话";
            };
        }

        public String FOLD_THIS_GENIES_CONVERSATIONS_AWAY() {
            return switch (lang) {
                case ENGLISH -> "Fold this genie's conversations away";
                case GERMAN -> "Die Gespräche dieses Dschinns einklappen";
                case SPANISH -> "Plegar las conversaciones de este genio";
                case JAPANESE -> "このジーニーとの会話を折りたたむ";
                case CHINESE -> "收起与此精灵的对话";
            };
        }

        public String SCHEDULED_RUNS(int count) {
            return switch (lang) {
                case ENGLISH -> count == 0 ? "" : count == 1 ? "1 scheduled run" : count + " scheduled runs";
                case GERMAN -> count == 0 ? "" : count == 1 ? "1 geplanter Lauf" : count + " geplante Läufe";
                case SPANISH -> count == 0 ? "" : count == 1 ? "1 ejecución programada" : count + " ejecuciones programadas";
                case JAPANESE -> count == 0 ? "" : "スケジュール実行 " + count + " 件";
                case CHINESE -> count == 0 ? "" : count + " 次计划运行";
            };
        }

        public String SHOW_OR_HIDE_THE_CONVERSATIONS_OF_SCHEDULED_JOBS() {
            return switch (lang) {
                case ENGLISH -> "Show or hide the conversations the runs of this genie's scheduled jobs had";
                case GERMAN -> "Die Gespräche zeigen oder ausblenden, die dieser Dschinn bei seinen geplanten Aufträgen geführt hat";
                case SPANISH -> "Mostrar u ocultar las conversaciones que tuvo este genio al hacer sus tareas programadas";
                case JAPANESE -> "このジーニーがスケジュールされたジョブを実行したときの会話を表示／非表示";
                case CHINESE -> "显示或隐藏此精灵执行计划任务时的对话";
            };
        }

        public String CONVERSATIONS(int count) {
            return switch (lang) {
                case ENGLISH -> count == 0 ? "no conversations yet" : count == 1 ? "1 conversation" : count + " conversations";
                case GERMAN -> count == 0 ? "noch keine Gespräche" : count == 1 ? "1 Gespräch" : count + " Gespräche";
                case SPANISH -> count == 0 ? "aún no hay conversaciones" : count == 1 ? "1 conversación" : count + " conversaciones";
                case JAPANESE -> count == 0 ? "まだ会話はありません" : "会話 " + count + " 件";
                case CHINESE -> count == 0 ? "还没有对话" : count + " 个对话";
            };
        }

        public String SHOW_OR_HIDE_YOUR_CONVERSATIONS_WITH_THIS_GENIE() {
            return switch (lang) {
                case ENGLISH -> "Show or hide your conversations with this genie";
                case GERMAN -> "Deine Gespräche mit diesem Dschinn zeigen oder ausblenden";
                case SPANISH -> "Mostrar u ocultar tus conversaciones con este genio";
                case JAPANESE -> "このジーニーとの会話を表示／非表示";
                case CHINESE -> "显示或隐藏你与此精灵的对话";
            };
        }

        public String START_A_NEW_CONVERSATION_WITH_THIS_GENIE() {
            return switch (lang) {
                case ENGLISH -> "Start a new conversation with this genie; the others are kept";
                case GERMAN -> "Ein neues Gespräch mit diesem Dschinn beginnen; die anderen bleiben erhalten";
                case SPANISH -> "Empezar una conversación nueva con este genio; las demás se conservan";
                case JAPANESE -> "このジーニーと新しい会話を始めます。ほかの会話は残ります";
                case CHINESE -> "与此精灵开始新对话，其他对话都会保留";
            };
        }

        public String DELETE_ELLIPSIS() {
            return switch (lang) {
                case ENGLISH -> "Delete…";
                case GERMAN -> "Löschen…";
                case SPANISH -> "Eliminar…";
                case JAPANESE -> "削除…";
                case CHINESE -> "删除…";
            };
        }

        public String DELETE_THE_CONVERSATION_THIS_GENIE_IS_IN() {
            return switch (lang) {
                case ENGLISH -> "Delete the conversation this genie is in, with all its branches";
                case GERMAN -> "Das Gespräch, in dem dieser Dschinn gerade ist, mit allen Zweigen löschen";
                case SPANISH -> "Eliminar la conversación en la que está este genio, con todas sus ramas";
                case JAPANESE -> "このジーニーが今いる会話を、すべての分岐ごと削除します";
                case CHINESE -> "删除此精灵当前所在的对话及其所有分支";
            };
        }

        public String ITS_FIRST_QUESTION_WAS_ASKED_DIFFERENTLY() {
            return switch (lang) {
                case ENGLISH -> "its first question was asked differently";
                case GERMAN -> "die erste Frage wurde anders gestellt";
                case SPANISH -> "su primera pregunta se planteó de otra forma";
                case JAPANESE -> "最初の質問を言い換えました";
                case CHINESE -> "第一个问题换了种问法";
            };
        }

        public String QUESTIONS_THEN_ASKED_DIFFERENTLY(int questions, int differently) {
            return switch (lang) {
                case ENGLISH ->
                    (questions == 1 ? "one question" : questions + " questions")
                    + (differently == 0 ? "" : ", then asked differently " + (differently == 1 ? "once" : differently + " times"));
                case GERMAN -> (questions == 1 ? "eine Frage" : questions + " Fragen") + (differently == 0 ? "" : ", dann " + (differently == 1 ? "einmal" : differently + "-mal") + " anders gestellt");
                case SPANISH -> (questions == 1 ? "una pregunta" : questions + " preguntas") + (differently == 0 ? "" : ", luego planteada de otra forma " + (differently == 1 ? "una vez" : differently + " veces"));
                case JAPANESE -> "質問 " + questions + " 件" + (differently == 0 ? "" : "、言い換え " + differently + " 回");
                case CHINESE -> questions + " 个问题" + (differently == 0 ? "" : "，之后换了 " + differently + " 次问法");
            };
        }

        public String DRAG_TO_MAKE_THIS_LIST_TALLER_OR_SHORTER() {
            return switch (lang) {
                case ENGLISH -> "Drag to make this list taller or shorter";
                case GERMAN -> "Ziehen, um diese Liste höher oder niedriger zu machen";
                case SPANISH -> "Arrastra para hacer esta lista más alta o más baja";
                case JAPANESE -> "ドラッグしてこのリストの高さを変えます";
                case CHINESE -> "拖动以调整此列表的高度";
            };
        }

        public String HIDE_YOUR_GENIES() {
            return switch (lang) {
                case ENGLISH -> "Hide your genies";
                case GERMAN -> "Deine Dschinns ausblenden";
                case SPANISH -> "Ocultar tus genios";
                case JAPANESE -> "ジーニーの一覧を隠す";
                case CHINESE -> "隐藏精灵列表";
            };
        }

        public String SHOW_YOUR_GENIES() {
            return switch (lang) {
                case ENGLISH -> "Show your genies";
                case GERMAN -> "Deine Dschinns zeigen";
                case SPANISH -> "Mostrar tus genios";
                case JAPANESE -> "ジーニーの一覧を表示";
                case CHINESE -> "显示精灵列表";
            };
        }

        public String RENAME_ELLIPSIS() {
            return switch (lang) {
                case ENGLISH -> "Rename…";
                case GERMAN -> "Umbenennen…";
                case SPANISH -> "Cambiar el nombre…";
                case JAPANESE -> "名前を変更…";
                case CHINESE -> "重命名…";
            };
        }

        public String NEW_CONVERSATION() {
            return switch (lang) {
                case ENGLISH -> "New conversation";
                case GERMAN -> "Neues Gespräch";
                case SPANISH -> "Nueva conversación";
                case JAPANESE -> "新しい会話";
                case CHINESE -> "新对话";
            };
        }

        public String SAVE_NOW_ELLIPSIS() {
            return switch (lang) {
                case ENGLISH -> "Save now…";
                case GERMAN -> "Jetzt sichern…";
                case SPANISH -> "Guardar ahora…";
                case JAPANESE -> "今すぐ保存…";
                case CHINESE -> "立即保存…";
            };
        }

        public String LINK_ITS_WORKSPACE_INTO(String folder) {
            return switch (lang) {
                case ENGLISH -> "Link its workspace into " + folder;
                case GERMAN -> "Arbeitsbereich in " + folder + " verknüpfen";
                case SPANISH -> "Enlazar su espacio de trabajo en " + folder;
                case JAPANESE -> "ワークスペースを " + folder + " にリンク";
                case CHINESE -> "将其工作区链接到 " + folder;
            };
        }

        public String SLEEP() {
            return switch (lang) {
                case ENGLISH -> "Sleep";
                case GERMAN -> "Schlafen legen";
                case SPANISH -> "Dormir";
                case JAPANESE -> "眠らせる";
                case CHINESE -> "休眠";
            };
        }

        public String WAKE() {
            return switch (lang) {
                case ENGLISH -> "Wake";
                case GERMAN -> "Wecken";
                case SPANISH -> "Despertar";
                case JAPANESE -> "起こす";
                case CHINESE -> "唤醒";
            };
        }

        public String TRY_WAKING_AGAIN() {
            return switch (lang) {
                case ENGLISH -> "Try waking again";
                case GERMAN -> "Noch einmal wecken";
                case SPANISH -> "Intentar despertarlo otra vez";
                case JAPANESE -> "もう一度起こす";
                case CHINESE -> "再次尝试唤醒";
            };
        }

        public String REMOVE_FROM_GENIES_ELLIPSIS() {
            return switch (lang) {
                case ENGLISH -> "Remove from Genies…";
                case GERMAN -> "Aus Genies entfernen…";
                case SPANISH -> "Quitar de Genies…";
                case JAPANESE -> "Genies から外す…";
                case CHINESE -> "从 Genies 中移除…";
            };
        }

        public String DELETE_GENIE_ELLIPSIS(String name) {
            return switch (lang) {
                case ENGLISH -> "Delete " + name + "…";
                case GERMAN -> name + " löschen…";
                case SPANISH -> "Eliminar a " + name + "…";
                case JAPANESE -> name + " を削除…";
                case CHINESE -> "删除 " + name + "…";
            };
        }

        public String DOING_A_SCHEDULED_JOB() {
            return switch (lang) {
                case ENGLISH -> "doing a scheduled job";
                case GERMAN -> "erledigt einen geplanten Auftrag";
                case SPANISH -> "está haciendo una tarea programada";
                case JAPANESE -> "スケジュールされたジョブを実行中";
                case CHINESE -> "正在执行计划任务";
            };
        }

        public String A_CONVERSATION_IN_WHICH_NOTHING_WAS_ASKED_YET() {
            return switch (lang) {
                case ENGLISH -> "New conversation";
                case GERMAN -> "Neues Gespräch";
                case SPANISH -> "Nueva conversación";
                case JAPANESE -> "新しい会話";
                case CHINESE -> "新对话";
            };
        }
    }

    /// The words of the header above a genie's pages, and of what its lamp asks the user to allow
    /// under it.
    public record Header(Lang lang) {
        public String SOMETHING_WENT_WRONG_IN_GENIES_NOT_ONLY_IN_THIS_GENIE() {
            return switch (lang) {
                case ENGLISH -> "Something went wrong in Genies, not only in this genie. Click to see what happened";
                case GERMAN -> "In Genies ist etwas schiefgelaufen, nicht nur bei diesem Dschinn. Klicken, um zu sehen, was passiert ist";
                case SPANISH -> "Algo salió mal en Genies, no solo en este genio. Haz clic para ver qué pasó";
                case JAPANESE -> "このジーニーに限らず、Genies で問題が発生しました。クリックすると何が起きたかを表示します";
                case CHINESE -> "Genies 出了问题，不只是这个精灵。点击查看发生了什么";
            };
        }

        public String DOUBLE_CLICK_TO_RENAME() {
            return switch (lang) {
                case ENGLISH -> "Double-click to rename";
                case GERMAN -> "Doppelklicken zum Umbenennen";
                case SPANISH -> "Doble clic para cambiar el nombre";
                case JAPANESE -> "ダブルクリックで名前を変更";
                case CHINESE -> "双击以重命名";
            };
        }

        public String TOKENS(int count) {
            return switch (lang) {
                case ENGLISH -> count == 0 ? "" : String.format("%,d tokens", count);
                case GERMAN -> count == 0 ? "" : String.format(Locale.GERMAN, "%,d Tokens", count);
                case SPANISH -> count == 0 ? "" : String.format(Locale.of("es"), "%,d tokens", count);
                case JAPANESE -> count == 0 ? "" : String.format(Locale.JAPANESE, "%,d トークン", count);
                case CHINESE -> count == 0 ? "" : String.format(Locale.CHINESE, "%,d 个 token", count);
            };
        }

        public String TOKENS_THE_MODEL_COUNTED_FOR_THIS_GENIE_SINCE_GENIES_STARTED() {
            return switch (lang) {
                case ENGLISH -> "Tokens the model counted for this genie since Genies started";
                case GERMAN -> "Tokens, die das Modell für diesen Dschinn gezählt hat, seit Genies läuft";
                case SPANISH -> "Tokens que el modelo ha contado para este genio desde que se abrió Genies";
                case JAPANESE -> "Genies の起動後に、モデルがこのジーニーについて数えたトークン数";
                case CHINESE -> "自 Genies 启动以来，模型为此精灵统计的 token 数";
            };
        }

        public String DESKTOP() {
            return switch (lang) {
                case ENGLISH -> "Desktop";
                case GERMAN -> "Desktop";
                case SPANISH -> "Escritorio";
                case JAPANESE -> "デスクトップ";
                case CHINESE -> "桌面";
            };
        }

        public String DESKTOP_WATCH_THE_GENIES_DESKTOP_AND_USE_IT() {
            return switch (lang) {
                case ENGLISH -> "Desktop: watch the genie's desktop, and use it";
                case GERMAN -> "Desktop: dem Dschinn auf den Desktop schauen und ihn mitbenutzen";
                case SPANISH -> "Escritorio: mira el escritorio del genio y úsalo";
                case JAPANESE -> "デスクトップ：ジーニーのデスクトップを見たり、操作したりできます";
                case CHINESE -> "桌面：查看并使用精灵的桌面";
            };
        }

        public String STOP() {
            return switch (lang) {
                case ENGLISH -> "Stop";
                case GERMAN -> "Anhalten";
                case SPANISH -> "Detener";
                case JAPANESE -> "停止";
                case CHINESE -> "停止";
            };
        }

        public String STOP_WHAT_THE_GENIE_IS_DOING() {
            return switch (lang) {
                case ENGLISH -> "Stop what the genie is doing";
                case GERMAN -> "Anhalten, was der Dschinn gerade tut";
                case SPANISH -> "Detener lo que está haciendo el genio";
                case JAPANESE -> "ジーニーが今していることを止めます";
                case CHINESE -> "停止精灵正在做的事";
            };
        }

        public String SLEEP() {
            return switch (lang) {
                case ENGLISH -> "Sleep";
                case GERMAN -> "Schlafen legen";
                case SPANISH -> "Dormir";
                case JAPANESE -> "眠らせる";
                case CHINESE -> "休眠";
            };
        }

        public String SLEEP_END_THE_GENIES_SANDBOX() {
            return switch (lang) {
                case ENGLISH -> "Sleep: end the genie's sandbox. Its home and this conversation are kept.";
                case GERMAN -> "Schlafen legen: die Sandbox des Dschinns beenden. Sein Zuhause und dieses Gespräch bleiben erhalten.";
                case SPANISH -> "Dormir: cierra el sandbox del genio. Su hogar y esta conversación se conservan.";
                case JAPANESE -> "眠らせる：ジーニーのサンドボックスを終了します。ホームとこの会話は残ります。";
                case CHINESE -> "休眠：结束精灵的沙箱。它的主目录和此对话都会保留。";
            };
        }

        public String ASKS_YOU_TO_ALLOW(String who, String what) {
            return switch (lang) {
                case ENGLISH -> who + " asks you to allow: " + what;
                case GERMAN -> who + " bittet dich um Erlaubnis: " + what;
                case SPANISH -> who + " te pide permiso para: " + what;
                case JAPANESE -> who + " が許可を求めています：" + what;
                case CHINESE -> who + " 请求你允许：" + what;
            };
        }

        public String A_MODEL_ALLOWED_IT_ALREADY_THIS_ONE_NEEDS_YOU_TOO() {
            return switch (lang) {
                case ENGLISH ->
                    "A model allowed it already; this one needs you too. "
                    + "Nothing happens until you decide, and after a minute it is refused.";
                case GERMAN -> "Ein Modell hat es schon erlaubt, aber hier zählt auch deine Zustimmung. Bis du entscheidest, passiert nichts, und nach einer Minute wird es abgelehnt.";
                case SPANISH -> "Un modelo ya lo ha permitido, pero esto también necesita tu aprobación. No pasa nada hasta que decidas, y al cabo de un minuto se rechaza.";
                case JAPANESE -> "モデルはすでに許可しましたが、これにはあなたの許可も必要です。決めるまでは何も起こらず、1 分たつと拒否されます。";
                case CHINESE -> "模型已经允许了，但这一项还需要你同意。在你决定之前什么都不会发生，一分钟后将自动拒绝。";
            };
        }

        public String REFUSE() {
            return switch (lang) {
                case ENGLISH -> "Refuse";
                case GERMAN -> "Ablehnen";
                case SPANISH -> "Rechazar";
                case JAPANESE -> "拒否";
                case CHINESE -> "拒绝";
            };
        }

        public String ALLOW() {
            return switch (lang) {
                case ENGLISH -> "Allow";
                case GERMAN -> "Erlauben";
                case SPANISH -> "Permitir";
                case JAPANESE -> "許可";
                case CHINESE -> "允许";
            };
        }

        public String MANAGE_THE_GENIES_ACTIONS_SCHEDULE_HISTORY_AND_HOST_ACCESS() {
            return switch (lang) {
                case ENGLISH -> "Manage: the genie's actions, schedule, history and host access";
                case GERMAN -> "Verwalten: Aktionen, Zeitplan, Verlauf und Rechnerzugriff des Dschinns";
                case SPANISH -> "Gestionar: las acciones, la programación, el historial y el acceso al equipo del genio";
                case JAPANESE -> "管理：ジーニーの操作、スケジュール、履歴、ホストへのアクセス";
                case CHINESE -> "管理：精灵的操作、计划、历史和主机访问";
            };
        }

        public String CHAT() {
            return switch (lang) {
                case ENGLISH -> "Chat";
                case GERMAN -> "Chat";
                case SPANISH -> "Chat";
                case JAPANESE -> "チャット";
                case CHINESE -> "聊天";
            };
        }

        public String ACTIONS() {
            return switch (lang) {
                case ENGLISH -> "Actions";
                case GERMAN -> "Aktionen";
                case SPANISH -> "Acciones";
                case JAPANESE -> "操作";
                case CHINESE -> "操作";
            };
        }

        public String SCHEDULE() {
            return switch (lang) {
                case ENGLISH -> "Schedule";
                case GERMAN -> "Zeitplan";
                case SPANISH -> "Programación";
                case JAPANESE -> "スケジュール";
                case CHINESE -> "计划";
            };
        }

        public String HISTORY() {
            return switch (lang) {
                case ENGLISH -> "History";
                case GERMAN -> "Verlauf";
                case SPANISH -> "Historial";
                case JAPANESE -> "履歴";
                case CHINESE -> "历史";
            };
        }

        public String ACCESS() {
            return switch (lang) {
                case ENGLISH -> "Access";
                case GERMAN -> "Zugriff";
                case SPANISH -> "Acceso";
                case JAPANESE -> "アクセス";
                case CHINESE -> "访问";
            };
        }

        public String CHAT_TALK_WITH_THE_GENIE() {
            return switch (lang) {
                case ENGLISH -> "Chat: talk with the genie";
                case GERMAN -> "Chat: mit dem Dschinn reden";
                case SPANISH -> "Chat: habla con el genio";
                case JAPANESE -> "チャット：ジーニーと話す";
                case CHINESE -> "聊天：与精灵交谈";
            };
        }

        public String ACTIONS_RENAME_SAVE_WAKE_OR_SLEEP_LINK_ITS_FOLDERS_OR_DELETE_THE_GENIE() {
            return switch (lang) {
                case ENGLISH -> "Actions: rename, save, wake or sleep, link its folders, or delete the genie";
                case GERMAN -> "Aktionen: den Dschinn umbenennen, sichern, wecken oder schlafen legen, seine Ordner verknüpfen oder ihn löschen";
                case SPANISH -> "Acciones: cambiar el nombre del genio, guardarlo, despertarlo o dormirlo, enlazar sus carpetas o eliminarlo";
                case JAPANESE -> "操作：ジーニーの名前の変更、保存、起こす・眠らせる、フォルダーのリンク、削除";
                case CHINESE -> "操作：重命名、保存、唤醒或休眠精灵，链接其文件夹，或删除它";
            };
        }

        public String SCHEDULE_WHEN_JOBS_WAKE_THE_GENIE_AND_WHAT_THEY_DID() {
            return switch (lang) {
                case ENGLISH -> "Schedule: when jobs wake the genie, and what they did";
                case GERMAN -> "Zeitplan: wann Aufträge den Dschinn wecken und was sie getan haben";
                case SPANISH -> "Programación: cuándo despiertan las tareas al genio y qué hicieron";
                case JAPANESE -> "スケジュール：ジョブがジーニーを起こすタイミングと、ジョブの結果";
                case CHINESE -> "计划：任务何时唤醒精灵，以及它们做了什么";
            };
        }

        public String HISTORY_THE_MOMENTS_THE_GENIES_HOME_WAS_SAVED_AT() {
            return switch (lang) {
                case ENGLISH -> "History: the moments the genie's home was saved at, and going back to one";
                case GERMAN -> "Verlauf: die gesicherten Stände des Dschinns, und zu einem davon zurückkehren";
                case SPANISH -> "Historial: los momentos en que se guardó el hogar del genio, y volver a uno de ellos";
                case JAPANESE -> "履歴：ジーニーのホームを保存した時点と、その時点に戻ること";
                case CHINESE -> "历史：精灵主目录的各个保存点，以及回到其中之一";
            };
        }

        public String HOST_ACCESS_WHETHER_THE_GENIE_MAY_REACH_THIS_COMPUTER() {
            return switch (lang) {
                case ENGLISH -> "Host access: whether the genie may reach this computer, and its rules";
                case GERMAN -> "Rechnerzugriff: ob der Dschinn auf diesen Rechner zugreifen darf, und nach welchen Regeln";
                case SPANISH -> "Acceso al equipo: si el genio puede acceder a este equipo, y con qué reglas";
                case JAPANESE -> "ホストへのアクセス：ジーニーがこのコンピューターにアクセスできるかどうかと、そのルール";
                case CHINESE -> "主机访问：精灵能否访问这台电脑，以及相关规则";
            };
        }
    }

    /// The words of a genie's desktop, beside its chat, and of how it is shown.
    public record Desktop(Lang lang) {
        public String DRAG_TO_SHARE_THE_ROOM_BETWEEN_THE_CHAT_AND_THE_DESKTOP() {
            return switch (lang) {
                case ENGLISH ->
                    "Drag to share the room between the chat and the desktop, or all the way to close the desktop."
                    + " Double-click to let Genies share it";
                case GERMAN -> "Ziehen, um den Platz zwischen Chat und Desktop aufzuteilen, oder ganz zur Seite, um den Desktop zu schließen. Doppelklicken, damit Genies ihn aufteilt";
                case SPANISH -> "Arrastra para repartir el espacio entre el chat y el escritorio, o hasta el borde para cerrar el escritorio. Doble clic para que Genies lo reparta";
                case JAPANESE -> "ドラッグしてチャットとデスクトップの幅を分けます。端までドラッグするとデスクトップを閉じます。ダブルクリックすると Genies が調整します";
                case CHINESE -> "拖动以分配聊天与桌面的空间，拖到尽头可关闭桌面。双击则由 Genies 自动分配";
            };
        }

        public String FITTED() {
            return switch (lang) {
                case ENGLISH -> "fitted";
                case GERMAN -> "eingepasst";
                case SPANISH -> "ajustado";
                case JAPANESE -> "フィット";
                case CHINESE -> "已适配";
            };
        }

        public String RECORDED_SO_IT_KEEPS_ITS_OWN_SIZE() {
            return switch (lang) {
                case ENGLISH -> "Recorded, so it keeps its own size";
                case GERMAN -> "Wird aufgezeichnet und behält deshalb seine eigene Größe";
                case SPANISH -> "Se está grabando, así que conserva su propio tamaño";
                case JAPANESE -> "録画中のため、元のサイズのままです";
                case CHINESE -> "正在录制，因此保持原有尺寸";
            };
        }

        public String CLICK_THE_DESKTOP_TO_USE_IT() {
            return switch (lang) {
                case ENGLISH -> "Click the desktop to use it";
                case GERMAN -> "Klicke auf den Desktop, um ihn zu benutzen";
                case SPANISH -> "Haz clic en el escritorio para usarlo";
                case JAPANESE -> "クリックするとデスクトップを操作できます";
                case CHINESE -> "点击桌面即可使用";
            };
        }

        public String SHOWS_YOU(String who, String what) {
            return switch (lang) {
                case ENGLISH -> who + " shows you: " + what;
                case GERMAN -> who + " zeigt dir: " + what;
                case SPANISH -> who + " te muestra: " + what;
                case JAPANESE -> who + " が表示中：" + what;
                case CHINESE -> who + " 正在展示：" + what;
            };
        }

        public String FIT() {
            return switch (lang) {
                case ENGLISH -> "Fit";
                case GERMAN -> "Einpassen";
                case SPANISH -> "Ajustar";
                case JAPANESE -> "フィット";
                case CHINESE -> "适配";
            };
        }

        public String GIVE_THE_DESKTOP_THE_SIZE_OF_THIS_PANEL() {
            return switch (lang) {
                case ENGLISH ->
                    "Give the desktop the size of this panel, so what the genie shows fills it."
                    + " It gets its own size back when you close it.";
                case GERMAN -> "Dem Desktop die Größe dieses Bereichs geben, damit das, was der Dschinn zeigt, ihn ausfüllt. Beim Schließen bekommt er seine eigene Größe zurück.";
                case SPANISH -> "Da al escritorio el tamaño de este panel, para que lo que muestra el genio lo llene. Recupera su tamaño cuando lo cierras.";
                case JAPANESE -> "デスクトップをこのパネルの大きさにして、ジーニーの表示でパネルを満たします。閉じると元の大きさに戻ります。";
                case CHINESE -> "让桌面采用此面板的大小，使精灵展示的内容填满面板。关闭后会恢复原有大小。";
            };
        }

        public String FIXED() {
            return switch (lang) {
                case ENGLISH -> "Fixed";
                case GERMAN -> "Fest";
                case SPANISH -> "Fijo";
                case JAPANESE -> "固定";
                case CHINESE -> "固定";
            };
        }

        public String SHOW_THE_WHOLE_DESKTOP_AT_ITS_OWN_SIZE() {
            return switch (lang) {
                case ENGLISH -> "Show the whole desktop at its own size, shrunk into this panel";
                case GERMAN -> "Den ganzen Desktop in seiner eigenen Größe zeigen, verkleinert auf diesen Bereich";
                case SPANISH -> "Mostrar el escritorio entero a su propio tamaño, reducido para que quepa en este panel";
                case JAPANESE -> "デスクトップ全体を元の大きさのまま、このパネルに縮小して表示します";
                case CHINESE -> "按原有尺寸显示整个桌面，并缩放到此面板中";
            };
        }

        public String SMALLER_OR_CTRL_AND_THE_MOUSE_WHEEL() {
            return switch (lang) {
                case ENGLISH -> "Smaller (or Ctrl and the mouse wheel on the desktop)";
                case GERMAN -> "Kleiner (oder Strg und Mausrad auf dem Desktop)";
                case SPANISH -> "Más pequeño (o Ctrl y la rueda del ratón sobre el escritorio)";
                case JAPANESE -> "縮小（デスクトップ上で Ctrl ＋ マウスホイールでも可）";
                case CHINESE -> "缩小（也可在桌面上按住 Ctrl 并滚动鼠标滚轮）";
            };
        }

        public String LARGER_OR_CTRL_AND_THE_MOUSE_WHEEL() {
            return switch (lang) {
                case ENGLISH -> "Larger (or Ctrl and the mouse wheel on the desktop)";
                case GERMAN -> "Größer (oder Strg und Mausrad auf dem Desktop)";
                case SPANISH -> "Más grande (o Ctrl y la rueda del ratón sobre el escritorio)";
                case JAPANESE -> "拡大（デスクトップ上で Ctrl ＋ マウスホイールでも可）";
                case CHINESE -> "放大（也可在桌面上按住 Ctrl 并滚动鼠标滚轮）";
            };
        }

        public String KEYS() {
            return switch (lang) {
                case ENGLISH -> "Keys";
                case GERMAN -> "Tasten";
                case SPANISH -> "Teclas";
                case JAPANESE -> "キー";
                case CHINESE -> "快捷键";
            };
        }

        public String THE_KEYS_THAT_MAKE_A_WINDOW_ON_THE_DESKTOP_FULLSCREEN() {
            return switch (lang) {
                case ENGLISH -> "The keys that make a window on the desktop fullscreen, close it or minimize it";
                case GERMAN -> "Die Tasten, mit denen du ein Fenster auf dem Desktop als Vollbild zeigst, schließt oder minimierst";
                case SPANISH -> "Las teclas que ponen una ventana del escritorio a pantalla completa, la cierran o la minimizan";
                case JAPANESE -> "デスクトップ上のウィンドウを全画面表示にする、閉じる、最小化するためのキー";
                case CHINESE -> "用于将桌面上的窗口全屏、关闭或最小化的快捷键";
            };
        }

        public String KEYS_FOR_THE_DESKTOP() {
            return switch (lang) {
                case ENGLISH -> "Keys for the desktop";
                case GERMAN -> "Tasten für den Desktop";
                case SPANISH -> "Teclas del escritorio";
                case JAPANESE -> "デスクトップのキー操作";
                case CHINESE -> "桌面快捷键";
            };
        }

        public String CLICK_A_WINDOW_FIRST_SO_THAT_IT_HAS_THE_KEYBOARD() {
            return switch (lang) {
                case ENGLISH -> "Click a window first, so that it has the keyboard.";
                case GERMAN -> "Klicke zuerst auf ein Fenster, damit die Tasten dort ankommen.";
                case SPANISH -> "Primero haz clic en una ventana, para que reciba el teclado.";
                case JAPANESE -> "先にウィンドウをクリックして、キー入力がそのウィンドウに届くようにしてください。";
                case CHINESE -> "请先点击一个窗口，让它获得键盘焦点。";
            };
        }

        public String FULLSCREEN_AND_BACK() {
            return switch (lang) {
                case ENGLISH -> "Fullscreen, and back";
                case GERMAN -> "Vollbild und zurück";
                case SPANISH -> "Pantalla completa, y volver";
                case JAPANESE -> "全画面表示と解除";
                case CHINESE -> "全屏 / 退出全屏";
            };
        }

        public String CLOSE_THE_WINDOW() {
            return switch (lang) {
                case ENGLISH -> "Close the window";
                case GERMAN -> "Fenster schließen";
                case SPANISH -> "Cerrar la ventana";
                case JAPANESE -> "ウィンドウを閉じる";
                case CHINESE -> "关闭窗口";
            };
        }

        public String MINIMIZE_THE_WINDOW() {
            return switch (lang) {
                case ENGLISH -> "Minimize the window";
                case GERMAN -> "Fenster minimieren";
                case SPANISH -> "Minimizar la ventana";
                case JAPANESE -> "ウィンドウを最小化";
                case CHINESE -> "最小化窗口";
            };
        }

        public String BRING_EVERY_MINIMIZED_WINDOW_BACK() {
            return switch (lang) {
                case ENGLISH -> "Bring every minimized window back";
                case GERMAN -> "Alle minimierten Fenster zurückholen";
                case SPANISH -> "Recuperar todas las ventanas minimizadas";
                case JAPANESE -> "最小化したウィンドウをすべて元に戻す";
                case CHINESE -> "恢复所有最小化的窗口";
            };
        }

        public String SUPER_IS_THE_KEY_WITH_THE_WINDOWS_LOGO() {
            return switch (lang) {
                case ENGLISH -> "Super is the key with the Windows logo.";
                case GERMAN -> "Super ist die Taste mit dem Windows-Logo.";
                case SPANISH -> "Super es la tecla con el logotipo de Windows.";
                case JAPANESE -> "Super は Windows ロゴのキーです。";
                case CHINESE -> "Super 键就是印有 Windows 徽标的键。";
            };
        }

        public String CTRL() {
            return switch (lang) {
                case ENGLISH -> "Ctrl";
                case GERMAN -> "Strg";
                case SPANISH -> "Ctrl";
                case JAPANESE -> "Ctrl";
                case CHINESE -> "Ctrl";
            };
        }

        public String SUPER() {
            return switch (lang) {
                case ENGLISH -> "Super";
                case GERMAN -> "Super";
                case SPANISH -> "Super";
                case JAPANESE -> "Super";
                case CHINESE -> "Super";
            };
        }

        public String THE_DESKTOP_SHOWS_HERE_WHILE_THE_GENIE_IS_AWAKE() {
            return switch (lang) {
                case ENGLISH -> "The desktop shows here while the genie is awake.";
                case GERMAN -> "Solange der Dschinn wach ist, erscheint hier sein Desktop.";
                case SPANISH -> "El escritorio aparece aquí mientras el genio está despierto.";
                case JAPANESE -> "ジーニーが起きている間、ここにデスクトップが表示されます。";
                case CHINESE -> "精灵醒着时，桌面会显示在这里。";
            };
        }

        public String CONNECTING_TO_THE_DESKTOP() {
            return switch (lang) {
                case ENGLISH -> "Connecting to the desktop…";
                case GERMAN -> "Verbinde mit dem Desktop…";
                case SPANISH -> "Conectando con el escritorio…";
                case JAPANESE -> "デスクトップに接続中…";
                case CHINESE -> "正在连接桌面…";
            };
        }

        public String THE_DESKTOP_WENT_AWAY(String why) {
            return switch (lang) {
                case ENGLISH -> "The desktop went away: " + why;
                case GERMAN -> "Die Verbindung zum Desktop ist abgerissen: " + why;
                case SPANISH -> "Se perdió la conexión con el escritorio: " + why;
                case JAPANESE -> "デスクトップとの接続が切れました：" + why;
                case CHINESE -> "与桌面的连接已断开：" + why;
            };
        }

        public String COULD_NOT_SHOW_THE_DESKTOP(String why) {
            return switch (lang) {
                case ENGLISH -> "Could not show the desktop: " + why;
                case GERMAN -> "Der Desktop ließ sich nicht anzeigen: " + why;
                case SPANISH -> "No se pudo mostrar el escritorio: " + why;
                case JAPANESE -> "デスクトップを表示できませんでした：" + why;
                case CHINESE -> "无法显示桌面：" + why;
            };
        }
    }

    /// The words of a genie's chat: the conversation, the files the genie hands out, and the
    /// composer under it.
    public record Chat(Lang lang) {
        public String IS_LISTENING(String name) {
            return switch (lang) {
                case ENGLISH -> name + " is listening.";
                case GERMAN -> name + " hört zu.";
                case SPANISH -> name + " te escucha.";
                case JAPANESE -> name + " が聞いています。";
                case CHINESE -> name + " 正在聆听。";
            };
        }

        public String IS_WAKING(String name) {
            return switch (lang) {
                case ENGLISH -> name + " is waking.";
                case GERMAN -> name + " wacht auf.";
                case SPANISH -> name + " se está despertando.";
                case JAPANESE -> name + " が起きようとしています。";
                case CHINESE -> name + " 正在醒来。";
            };
        }

        public String IS_IN_THE_LAMP(String name) {
            return switch (lang) {
                case ENGLISH -> name + " is in the lamp.";
                case GERMAN -> name + " ist in der Lampe.";
                case SPANISH -> name + " está en la lámpara.";
                case JAPANESE -> name + " はランプの中にいます。";
                case CHINESE -> name + " 在神灯里。";
            };
        }

        public String COULD_NOT_WAKE(String name) {
            return switch (lang) {
                case ENGLISH -> name + " could not wake.";
                case GERMAN -> name + " ließ sich nicht wecken.";
                case SPANISH -> name + " no pudo despertar.";
                case JAPANESE -> name + " を起こせませんでした。";
                case CHINESE -> name + " 没能醒来。";
            };
        }

        public String ASK_FOR_ANYTHING_IT_HAS_A_DESKTOP_AND_A_SHELL_OF_ITS_OWN() {
            return switch (lang) {
                case ENGLISH -> "Ask for anything. It has a desktop and a shell of its own, and hands you files it makes.";
                case GERMAN -> "Bitte ihn um alles, was du brauchst. Er hat einen eigenen Desktop und eine eigene Shell und gibt dir die Dateien, die er erstellt.";
                case SPANISH -> "Pídele lo que quieras. Tiene su propio escritorio y su propia terminal, y te entrega los archivos que crea.";
                case JAPANESE -> "何でも頼んでください。専用のデスクトップとシェルを持ち、作ったファイルを渡してくれます。";
                case CHINESE -> "有什么需要尽管说。它有自己的桌面和 shell，还会把创建的文件交给你。";
            };
        }

        public String ITS_SANDBOX_IS_STARTING_THE_VERY_FIRST_TIME_IT_IS_MADE() {
            return switch (lang) {
                case ENGLISH -> "Its sandbox is starting. The very first time, the sandbox is made, which takes several minutes.";
                case GERMAN -> "Seine Sandbox startet. Beim allerersten Mal wird sie erst angelegt, das dauert einige Minuten.";
                case SPANISH -> "Su sandbox se está iniciando. La primera vez hay que crearlo, y eso tarda varios minutos.";
                case JAPANESE -> "サンドボックスを起動しています。初回はサンドボックスの作成から行うため、数分かかります。";
                case CHINESE -> "它的沙箱正在启动。第一次需要先创建沙箱，这需要几分钟。";
            };
        }

        public String WAKE_IT_TO_TALK_IT_KEEPS_ITS_OWN_DESKTOP() {
            return switch (lang) {
                case ENGLISH -> "Wake it to talk. It keeps its own desktop, safely away from yours.";
                case GERMAN -> "Wecke ihn, um mit ihm zu reden. Er hat seinen eigenen Desktop, sicher getrennt von deinem.";
                case SPANISH -> "Despiértalo para hablar. Tiene su propio escritorio, a salvo y separado del tuyo.";
                case JAPANESE -> "話すには起こしてください。あなたのデスクトップから安全に切り離された、専用のデスクトップを持っています。";
                case CHINESE -> "唤醒它即可交谈。它有自己的桌面，与你的桌面安全隔离。";
            };
        }

        public String WHAT_WENT_WRONG_IS_SAID_BELOW_TRY_AGAIN_ONCE_THAT_IS_SORTED_OUT() {
            return switch (lang) {
                case ENGLISH -> "What went wrong is said below. Try again once that is sorted out.";
                case GERMAN -> "Was schiefgelaufen ist, steht unten. Versuch es noch einmal, sobald das behoben ist.";
                case SPANISH -> "Abajo se explica qué salió mal. Vuelve a intentarlo cuando esté resuelto.";
                case JAPANESE -> "問題の内容は下に表示されています。解決したら、もう一度お試しください。";
                case CHINESE -> "出错原因见下方。问题解决后请再试一次。";
            };
        }

        public String OUTBOX() {
            return switch (lang) {
                case ENGLISH -> "OUTBOX";
                case GERMAN -> "AUSGANG";
                case SPANISH -> "BANDEJA DE SALIDA";
                case JAPANESE -> "受け取ったファイル";
                case CHINESE -> "收到的文件";
            };
        }

        public String SAVE_SOMEWHERE_ON_THIS_COMPUTER(String file) {
            return switch (lang) {
                case ENGLISH -> "Save " + file + " somewhere on this computer";
                case GERMAN -> file + " irgendwo auf diesem Rechner speichern";
                case SPANISH -> "Guardar " + file + " en algún lugar de este equipo";
                case JAPANESE -> file + " をこのコンピューターに保存";
                case CHINESE -> "将 " + file + " 保存到这台电脑上";
            };
        }

        public String IS_ANSWERING_IN_ANOTHER_CONVERSATION(String name) {
            return switch (lang) {
                case ENGLISH -> name + " is answering in another conversation. Write here once it is done.";
                case GERMAN -> name + " antwortet gerade in einem anderen Gespräch. Schreib hier, sobald das erledigt ist.";
                case SPANISH -> name + " está respondiendo en otra conversación. Escribe aquí cuando termine.";
                case JAPANESE -> name + " は別の会話で回答中です。終わったら、ここに書いてください。";
                case CHINESE -> name + " 正在另一个对话中回答。等它答完再在这里输入。";
            };
        }

        public String GIVE_THE_GENIE_A_FILE_IT_LANDS_IN_INBOX() {
            return switch (lang) {
                case ENGLISH -> "Give the genie a file; it lands in ~/inbox";
                case GERMAN -> "Gib dem Dschinn eine Datei; sie landet in ~/inbox";
                case SPANISH -> "Dale un archivo al genio; lo recibe en ~/inbox";
                case JAPANESE -> "ジーニーにファイルを渡します。~/inbox に届きます";
                case CHINESE -> "给精灵一个文件，它会放进 ~/inbox";
            };
        }

        public String RETURN_SENDS_SHIFT_AND_RETURN_STARTS_A_NEW_LINE() {
            return switch (lang) {
                case ENGLISH -> "Return sends; Shift and Return starts a new line";
                case GERMAN -> "Eingabe sendet, Umschalt+Eingabe beginnt eine neue Zeile";
                case SPANISH -> "Intro envía; Mayús+Intro empieza una línea nueva";
                case JAPANESE -> "Enter で送信、Shift ＋ Enter で改行";
                case CHINESE -> "按 Enter 发送，按 Shift + Enter 换行";
            };
        }

        public String SEND() {
            return switch (lang) {
                case ENGLISH -> "Send";
                case GERMAN -> "Senden";
                case SPANISH -> "Enviar";
                case JAPANESE -> "送信";
                case CHINESE -> "发送";
            };
        }

        public String NEW_CONVERSATION() {
            return switch (lang) {
                case ENGLISH -> "New conversation";
                case GERMAN -> "Neues Gespräch";
                case SPANISH -> "Nueva conversación";
                case JAPANESE -> "新しい会話";
                case CHINESE -> "新对话";
            };
        }

        public String START_A_NEW_CONVERSATION_OR_SELECT_ONE_IN_THE_TREE_TO_RETURN_TO_IT() {
            return switch (lang) {
                case ENGLISH -> "Start a new conversation, or select a conversation in the tree to return to it";
                case GERMAN -> "Ein neues Gespräch beginnen, oder im Baum ein Gespräch auswählen, um dorthin zurückzukehren";
                case SPANISH -> "Empieza una conversación nueva, o selecciona una en el árbol para volver a ella";
                case JAPANESE -> "新しい会話を始めるか、ツリーで会話を選んでそこに戻ります";
                case CHINESE -> "开始新对话，或在树中选择一个对话以返回";
            };
        }

        public String WAKING(String name, String doing) {
            return switch (lang) {
                case ENGLISH -> "Waking " + name + ": " + doing + "…";
                case GERMAN -> name + " wird geweckt: " + doing + "…";
                case SPANISH -> "Despertando a " + name + ": " + doing + "…";
                case JAPANESE -> name + " を起こしています：" + doing + "…";
                case CHINESE -> "正在唤醒 " + name + "：" + doing + "…";
            };
        }

        public String IS_ASLEEP_ITS_SANDBOX_IS_OFF(String name) {
            return switch (lang) {
                case ENGLISH -> name + " is asleep. Its sandbox is off, its home and conversation are kept.";
                case GERMAN -> name + " schläft. Seine Sandbox ist aus, sein Zuhause und das Gespräch bleiben erhalten.";
                case SPANISH -> name + " está dormido. Su sandbox está apagado; su hogar y la conversación se conservan.";
                case JAPANESE -> name + " は眠っています。サンドボックスは止まっていますが、ホームと会話は残っています。";
                case CHINESE -> name + " 正在休眠。它的沙箱已关闭，主目录和对话都会保留。";
            };
        }

        public String COULD_NOT_WAKE_BECAUSE(String name, String why) {
            return switch (lang) {
                case ENGLISH -> name + " could not wake: " + why;
                case GERMAN -> name + " ließ sich nicht wecken: " + why;
                case SPANISH -> name + " no pudo despertar: " + why;
                case JAPANESE -> name + " を起こせませんでした：" + why;
                case CHINESE -> name + " 没能醒来：" + why;
            };
        }

        public String TRY_AGAIN() {
            return switch (lang) {
                case ENGLISH -> "Try again";
                case GERMAN -> "Noch einmal versuchen";
                case SPANISH -> "Volver a intentarlo";
                case JAPANESE -> "再試行";
                case CHINESE -> "重试";
            };
        }

        public String WAKE() {
            return switch (lang) {
                case ENGLISH -> "Wake";
                case GERMAN -> "Wecken";
                case SPANISH -> "Despertar";
                case JAPANESE -> "起こす";
                case CHINESE -> "唤醒";
            };
        }

        public String EDIT() {
            return switch (lang) {
                case ENGLISH -> "Edit";
                case GERMAN -> "Bearbeiten";
                case SPANISH -> "Editar";
                case JAPANESE -> "編集";
                case CHINESE -> "编辑";
            };
        }

        public String ASK_THIS_DIFFERENTLY_WHAT_FOLLOWED_IS_KEPT_AS_A_BRANCH() {
            return switch (lang) {
                case ENGLISH -> "Ask this differently. What followed is kept, as a branch in the genie's conversations.";
                case GERMAN -> "Anders fragen. Was danach kam, bleibt als Zweig in den Gesprächen des Dschinns erhalten.";
                case SPANISH -> "Pregúntalo de otra forma. Lo que siguió se conserva como una rama en las conversaciones del genio.";
                case JAPANESE -> "聞き直します。その後のやり取りは、ジーニーの会話の分岐として残ります。";
                case CHINESE -> "换种问法。之后的内容会作为分支保留在精灵的对话中。";
            };
        }

        public String PREVIOUS_CONVERSATION_VERSION() {
            return switch (lang) {
                case ENGLISH -> "Previous conversation version";
                case GERMAN -> "Vorherige Fassung des Gesprächs";
                case SPANISH -> "Versión anterior de la conversación";
                case JAPANESE -> "前の会話バージョン";
                case CHINESE -> "上一个对话版本";
            };
        }

        public String NEXT_CONVERSATION_VERSION() {
            return switch (lang) {
                case ENGLISH -> "Next conversation version";
                case GERMAN -> "Nächste Fassung des Gesprächs";
                case SPANISH -> "Versión siguiente de la conversación";
                case JAPANESE -> "次の会話バージョン";
                case CHINESE -> "下一个对话版本";
            };
        }

        public String COPY() {
            return switch (lang) {
                case ENGLISH -> "Copy";
                case GERMAN -> "Kopieren";
                case SPANISH -> "Copiar";
                case JAPANESE -> "コピー";
                case CHINESE -> "复制";
            };
        }

        public String COPY_THE_ANSWER_AS_THE_GENIE_WROTE_IT() {
            return switch (lang) {
                case ENGLISH -> "Copy the answer, as the genie wrote it";
                case GERMAN -> "Die Antwort so kopieren, wie der Dschinn sie geschrieben hat";
                case SPANISH -> "Copiar la respuesta tal como la escribió el genio";
                case JAPANESE -> "ジーニーが書いたとおりに回答をコピー";
                case CHINESE -> "按精灵写下的原样复制回答";
            };
        }

        public String THINKING() {
            return switch (lang) {
                case ENGLISH -> "thinking";
                case GERMAN -> "denkt nach";
                case SPANISH -> "pensando";
                case JAPANESE -> "考え中";
                case CHINESE -> "思考中";
            };
        }

        public String THOUGHT() {
            return switch (lang) {
                case ENGLISH -> "thought";
                case GERMAN -> "hat nachgedacht";
                case SPANISH -> "ha pensado";
                case JAPANESE -> "考えました";
                case CHINESE -> "已思考";
            };
        }

        public String CLICK_TO_FOLD_THE_THOUGHTS_AWAY() {
            return switch (lang) {
                case ENGLISH -> "Click to fold the thoughts away";
                case GERMAN -> "Klicken, um die Gedanken einzuklappen";
                case SPANISH -> "Haz clic para plegar lo que pensó";
                case JAPANESE -> "クリックで思考を折りたたむ";
                case CHINESE -> "点击收起思考过程";
            };
        }

        public String CLICK_TO_SEE_WHAT_THE_GENIE_THINKS() {
            return switch (lang) {
                case ENGLISH -> "Click to see what the genie thinks";
                case GERMAN -> "Klicken, um zu sehen, was der Dschinn denkt";
                case SPANISH -> "Haz clic para ver qué piensa el genio";
                case JAPANESE -> "クリックでジーニーの考えを表示";
                case CHINESE -> "点击查看精灵的思考过程";
            };
        }

        public String YOUR_MESSAGE_WAITS_UNTIL_THIS_JOB_IS_DONE(String job) {
            return switch (lang) {
                case ENGLISH -> job + ". The genie does one thing at a time, so your message waits until this job is done.";
                case GERMAN -> job + ". Der Dschinn erledigt eins nach dem anderen, deshalb wartet deine Nachricht, bis dieser Auftrag fertig ist.";
                case SPANISH -> job + ". El genio hace una cosa cada vez, así que tu mensaje espera a que termine esta tarea.";
                case JAPANESE -> job + "。ジーニーは一度に一つのことしかしないため、このジョブが終わるまでメッセージは待機します。";
                case CHINESE -> job + "。精灵一次只做一件事，所以你的消息要等这项任务完成后才会处理。";
            };
        }

        public String RUNNING() {
            return switch (lang) {
                case ENGLISH -> "running…";
                case GERMAN -> "läuft…";
                case SPANISH -> "en curso…";
                case JAPANESE -> "実行中…";
                case CHINESE -> "运行中…";
            };
        }

        public String FAILED() {
            return switch (lang) {
                case ENGLISH -> "failed";
                case GERMAN -> "fehlgeschlagen";
                case SPANISH -> "falló";
                case JAPANESE -> "失敗";
                case CHINESE -> "失败";
            };
        }

        public String CLICK_TO_SEE_WHAT_IT_PRINTED() {
            return switch (lang) {
                case ENGLISH -> "Click to see what it printed";
                case GERMAN -> "Klicken, um die Ausgabe zu sehen";
                case SPANISH -> "Haz clic para ver lo que mostró";
                case JAPANESE -> "クリックで出力を表示";
                case CHINESE -> "点击查看输出";
            };
        }

        public String SAVE_ELLIPSIS() {
            return switch (lang) {
                case ENGLISH -> "Save…";
                case GERMAN -> "Speichern…";
                case SPANISH -> "Guardar…";
                case JAPANESE -> "保存…";
                case CHINESE -> "保存…";
            };
        }

        public String IS_DOING_A_SCHEDULED_JOB_FIRST(String name, String job) {
            return switch (lang) {
                case ENGLISH -> name + " is doing a scheduled job first: " + job;
                case GERMAN -> name + " erledigt zuerst einen geplanten Auftrag: " + job;
                case SPANISH -> name + " primero está haciendo una tarea programada: " + job;
                case JAPANESE -> name + " は先にスケジュールされたジョブを実行中です：" + job;
                case CHINESE -> name + " 正在先执行一项计划任务：" + job;
            };
        }

        public String IS_THINKING(String name) {
            return switch (lang) {
                case ENGLISH -> name + " is thinking";
                case GERMAN -> name + " denkt nach";
                case SPANISH -> name + " está pensando";
                case JAPANESE -> name + " が考えています";
                case CHINESE -> name + " 正在思考";
            };
        }

        public String WRITTEN_BY(String model) {
            return switch (lang) {
                case ENGLISH -> "Written by " + model;
                case GERMAN -> "Geschrieben von " + model;
                case SPANISH -> "Escrito por " + model;
                case JAPANESE -> model + " が書きました";
                case CHINESE -> "由 " + model + " 撰写";
            };
        }
    }

    /// The words of the dialogs the window asks and tells the user things in.
    public record Dialogs(Lang lang) {
        public String DELETE_THE_CONVERSATION_FOR_GOOD_WITH_ALL_ITS_BRANCHES(String title) {
            return switch (lang) {
                case ENGLISH -> "Delete the conversation \"" + title + "\" for good, with all its branches?";
                case GERMAN -> "Das Gespräch „" + title + "“ mit allen Zweigen endgültig löschen?";
                case SPANISH -> "¿Eliminar para siempre la conversación «" + title + "», con todas sus ramas?";
                case JAPANESE -> "会話「" + title + "」を、すべての分岐ごと完全に削除しますか？";
                case CHINESE -> "要永久删除对话“" + title + "”及其所有分支吗？";
            };
        }

        public String DELETE_A_CONVERSATION() {
            return switch (lang) {
                case ENGLISH -> "Delete a conversation";
                case GERMAN -> "Gespräch löschen";
                case SPANISH -> "Eliminar una conversación";
                case JAPANESE -> "会話の削除";
                case CHINESE -> "删除对话";
            };
        }

        public String DELETE() {
            return switch (lang) {
                case ENGLISH -> "Delete";
                case GERMAN -> "Löschen";
                case SPANISH -> "Eliminar";
                case JAPANESE -> "削除";
                case CHINESE -> "删除";
            };
        }

        public String KEEP() {
            return switch (lang) {
                case ENGLISH -> "Keep";
                case GERMAN -> "Behalten";
                case SPANISH -> "Conservar";
                case JAPANESE -> "残す";
                case CHINESE -> "保留";
            };
        }

        public String A_NEW_NAME_FOR(String name) {
            return switch (lang) {
                case ENGLISH -> "A new name for " + name + ":";
                case GERMAN -> "Neuer Name für " + name + ":";
                case SPANISH -> "Nuevo nombre para " + name + ":";
                case JAPANESE -> name + " の新しい名前：";
                case CHINESE -> name + " 的新名称：";
            };
        }

        public String RENAME_A_GENIE() {
            return switch (lang) {
                case ENGLISH -> "Rename a genie";
                case GERMAN -> "Dschinn umbenennen";
                case SPANISH -> "Cambiar el nombre de un genio";
                case JAPANESE -> "ジーニーの名前の変更";
                case CHINESE -> "重命名精灵";
            };
        }

        public String WORKSPACE_IS_IN_YOUR_FOLDER_NOW_IT_IS_A_LINK_NOT_A_COPY(String genie, boolean now, String folder, String link) {
            return switch (lang) {
                case ENGLISH ->
                    genie + "'s workspace " + (now ? "is" : "was") + " in your " + folder + (now ? " now" : " already")
                    + ", as \"" + link + "\".\nIt is a link, not a copy: " + genie + " sees what you change there.";
                case GERMAN -> "Der Arbeitsbereich von " + genie + " " + (now ? "liegt jetzt" : "lag schon") + " in deinem Ordner " + folder + ", als „" + link + "“.\nEs ist eine Verknüpfung, keine Kopie: " + genie + " sieht, was du dort änderst.";
                case SPANISH -> "El espacio de trabajo de " + genie + " " + (now ? "ahora está" : "ya estaba") + " en tu carpeta " + folder + ", como «" + link + "».\nEs un enlace, no una copia: " + genie + " ve lo que cambies allí.";
                case JAPANESE -> genie + " のワークスペースは" + (now ? "" : "すでに") + "あなたの " + folder + " に「" + link + "」として" + (now ? "リンクされました" : "リンクされています") + "。\nコピーではなくリンクなので、そこで変更した内容は " + genie + " にも見えます。";
                case CHINESE -> genie + " 的工作区" + (now ? "现在" : "早已") + "以“" + link + "”的名称出现在你的 " + folder + " 中。\n这是链接而不是副本：你在那里做的修改，" + genie + " 都能看到。";
            };
        }

        public String LINKED() {
            return switch (lang) {
                case ENGLISH -> "Linked";
                case GERMAN -> "Verknüpft";
                case SPANISH -> "Enlazado";
                case JAPANESE -> "リンクしました";
                case CHINESE -> "已链接";
            };
        }

        public String COULD_NOT_LINK() {
            return switch (lang) {
                case ENGLISH -> "Could not link";
                case GERMAN -> "Verknüpfen fehlgeschlagen";
                case SPANISH -> "No se pudo enlazar";
                case JAPANESE -> "リンクできませんでした";
                case CHINESE -> "无法链接";
            };
        }

        public String THINGS_WENT_WRONG_THAT_GENIES_DID_NOT_EXPECT(int count) {
            return switch (lang) {
                case ENGLISH ->
                    count == 1
                    ? "Something went wrong that Genies did not expect."
                    : count + " things went wrong that Genies did not expect.";
                case GERMAN -> count == 1 ? "Etwas ist schiefgelaufen, womit Genies nicht gerechnet hat." : count + " Dinge sind schiefgelaufen, mit denen Genies nicht gerechnet hat.";
                case SPANISH -> count == 1 ? "Algo salió mal que Genies no esperaba." : count + " cosas salieron mal que Genies no esperaba.";
                case JAPANESE -> count == 1 ? "Genies が想定していなかった問題が発生しました。" : "Genies が想定していなかった問題が " + count + " 件発生しました。";
                case CHINESE -> count == 1 ? "发生了一个 Genies 没有预料到的问题。" : "发生了 " + count + " 个 Genies 没有预料到的问题。";
            };
        }

        public String WHAT_WENT_WRONG() {
            return switch (lang) {
                case ENGLISH -> "What went wrong";
                case GERMAN -> "Was schiefgelaufen ist";
                case SPANISH -> "Qué salió mal";
                case JAPANESE -> "発生した問題";
                case CHINESE -> "出了什么问题";
            };
        }

        public String GENIES_CARRIED_ON_ALL_OF_IT_IS_KEPT_IN(String log) {
            return switch (lang) {
                case ENGLISH -> "Genies carried on. All of it is kept in " + log + ", which helps whoever fixes it.";
                case GERMAN -> "Genies läuft weiter. Alles Nähere steht in " + log + " – das hilft dem, der es behebt.";
                case SPANISH -> "Genies sigue funcionando. Todo queda guardado en " + log + ", lo que ayudará a quien lo arregle.";
                case JAPANESE -> "Genies は動作を続けています。詳細はすべて " + log + " に記録されているので、修正する人の役に立ちます。";
                case CHINESE -> "Genies 仍在正常运行。完整信息已记录在 " + log + " 中，可供修复问题的人参考。";
            };
        }

        public String CLOSE() {
            return switch (lang) {
                case ENGLISH -> "Close";
                case GERMAN -> "Schließen";
                case SPANISH -> "Cerrar";
                case JAPANESE -> "閉じる";
                case CHINESE -> "关闭";
            };
        }

        public String REMOVE_FROM_GENIES_ITS_LAMP_STAYS_WHERE_IT_IS(String name, String where) {
            return switch (lang) {
                case ENGLISH ->
                    "Remove " + name + " from Genies? Its lamp stays where it is, at "
                    + where + ", as it is, running if it runs.\nAdd it again with \"Add a lamp from elsewhere…\".";
                case GERMAN -> name + " aus Genies entfernen? Seine Lampe bleibt, wo und wie sie ist, unter " + where + ", und läuft weiter, falls sie läuft.\nMit „Lampe von anderswo hinzufügen…“ holst du sie wieder dazu.";
                case SPANISH -> "¿Quitar a " + name + " de Genies? Su lámpara se queda donde está, en " + where + ", tal como está, y sigue en marcha si lo estaba.\nPuedes volver a añadirla con «Añadir una lámpara de otro sitio…».";
                case JAPANESE -> name + " を Genies から外しますか？ランプは " + where + " にそのまま残り、動いていれば動き続けます。\n「ほかの場所のランプを追加…」で、もう一度追加できます。";
                case CHINESE -> "要从 Genies 中移除 " + name + " 吗？它的神灯会原样留在 " + where + "，如果正在运行，也会继续运行。\n可以通过“添加其他位置的神灯…”重新添加。";
            };
        }

        public String REMOVE_A_LAMP_FROM_GENIES() {
            return switch (lang) {
                case ENGLISH -> "Remove a lamp from Genies";
                case GERMAN -> "Lampe aus Genies entfernen";
                case SPANISH -> "Quitar una lámpara de Genies";
                case JAPANESE -> "ランプを Genies から外す";
                case CHINESE -> "从 Genies 中移除神灯";
            };
        }

        public String REMOVE() {
            return switch (lang) {
                case ENGLISH -> "Remove";
                case GERMAN -> "Entfernen";
                case SPANISH -> "Quitar";
                case JAPANESE -> "外す";
                case CHINESE -> "移除";
            };
        }

        public String DELETE_FOR_GOOD_ITS_HOME_AND_YOUR_CONVERSATIONS_WITH_IT(String name) {
            return switch (lang) {
                case ENGLISH -> "Delete " + name + " for good? Its home, everything it made and your conversations with it are deleted.";
                case GERMAN -> name + " endgültig löschen? Sein Zuhause, alles, was er gemacht hat, und deine Gespräche mit ihm werden gelöscht.";
                case SPANISH -> "¿Eliminar a " + name + " para siempre? Se borrarán su hogar, todo lo que ha creado y tus conversaciones con él.";
                case JAPANESE -> name + " を完全に削除しますか？ホームと作ったものすべて、そしてこのジーニーとの会話が削除されます。";
                case CHINESE -> "要永久删除 " + name + " 吗？它的主目录、它创建的一切以及你与它的对话都将被删除。";
            };
        }

        public String DELETE_A_GENIE() {
            return switch (lang) {
                case ENGLISH -> "Delete a genie";
                case GERMAN -> "Dschinn löschen";
                case SPANISH -> "Eliminar un genio";
                case JAPANESE -> "ジーニーの削除";
                case CHINESE -> "删除精灵";
            };
        }

        public String ADD_A_LAMP_MADE_WITH_OILLAMP_AT() {
            return switch (lang) {
                case ENGLISH -> "Add a lamp made with `oillamp at`";
                case GERMAN -> "Eine mit `oillamp at` angelegte Lampe hinzufügen";
                case SPANISH -> "Añadir una lámpara creada con `oillamp at`";
                case JAPANESE -> "`oillamp at` で作ったランプを追加";
                case CHINESE -> "添加用 `oillamp at` 创建的神灯";
            };
        }

        public String ADD() {
            return switch (lang) {
                case ENGLISH -> "Add";
                case GERMAN -> "Hinzufügen";
                case SPANISH -> "Añadir";
                case JAPANESE -> "追加";
                case CHINESE -> "添加";
            };
        }

        public String NOT_A_LAMP() {
            return switch (lang) {
                case ENGLISH -> "Not a lamp";
                case GERMAN -> "Keine Lampe";
                case SPANISH -> "No es una lámpara";
                case JAPANESE -> "ランプではありません";
                case CHINESE -> "不是神灯";
            };
        }

        public String ASK_DIFFERENTLY(String name) {
            return switch (lang) {
                case ENGLISH -> "Ask " + name + " differently";
                case GERMAN -> name + " anders fragen";
                case SPANISH -> "Preguntar a " + name + " de otra forma";
                case JAPANESE -> name + " に聞き直す";
                case CHINESE -> "换种方式问 " + name;
            };
        }

        public String GIVE_A_FILE(String name) {
            return switch (lang) {
                case ENGLISH -> "Give " + name + " a file";
                case GERMAN -> name + " eine Datei geben";
                case SPANISH -> "Darle un archivo a " + name;
                case JAPANESE -> name + " にファイルを渡す";
                case CHINESE -> "给 " + name + " 一个文件";
            };
        }

        public String SAVE(String file) {
            return switch (lang) {
                case ENGLISH -> "Save " + file;
                case GERMAN -> file + " speichern";
                case SPANISH -> "Guardar " + file;
                case JAPANESE -> file + " を保存";
                case CHINESE -> "保存 " + file;
            };
        }
    }

    /// The words of a genie's Actions page: what can be done with the genie as a whole.
    public record ActionsPage(Lang lang) {
        public String ACTIONS() {
            return switch (lang) {
                case ENGLISH -> "Actions";
                case GERMAN -> "Aktionen";
                case SPANISH -> "Acciones";
                case JAPANESE -> "操作";
                case CHINESE -> "操作";
            };
        }

        public String WHAT_CAN_BE_DONE_WITH_IT_AS_A_WHOLE(String genie) {
            return switch (lang) {
                case ENGLISH -> "What can be done with " + genie + " as a whole. A right-click on its card in your list offers the same.";
                case GERMAN -> "Was du mit " + genie + " als Ganzem tun kannst. Ein Rechtsklick auf seine Karte in deiner Liste bietet dasselbe.";
                case SPANISH -> "Lo que puedes hacer con " + genie + " en conjunto. Con clic derecho sobre su tarjeta en tu lista tienes lo mismo.";
                case JAPANESE -> genie + " 全体に対してできる操作です。一覧のカードを右クリックしても同じ操作ができます。";
                case CHINESE -> "可对 " + genie + " 整体进行的操作。在列表中右键单击它的卡片，也能进行同样的操作。";
            };
        }

        public String NAME() {
            return switch (lang) {
                case ENGLISH -> "Name";
                case GERMAN -> "Name";
                case SPANISH -> "Nombre";
                case JAPANESE -> "名前";
                case CHINESE -> "名称";
            };
        }

        public String ENTER_KEEPS_THE_NEW_NAME_ESCAPE_THE_ONE_IT_HAD() {
            return switch (lang) {
                case ENGLISH -> "Enter keeps the new name, Escape the one it had.";
                case GERMAN -> "Eingabe übernimmt den neuen Namen, Esc behält den alten.";
                case SPANISH -> "Intro guarda el nombre nuevo; Esc conserva el que tenía.";
                case JAPANESE -> "Enter で新しい名前を確定、Esc で元の名前に戻します。";
                case CHINESE -> "按 Enter 保留新名称，按 Esc 恢复原名称。";
            };
        }

        public String WHAT_IT_IS_CALLED_IN_YOUR_LIST_AND_ABOVE_ITS_CHAT() {
            return switch (lang) {
                case ENGLISH -> "What it is called in your list and above its chat.";
                case GERMAN -> "So heißt er in deiner Liste und über seinem Chat.";
                case SPANISH -> "Cómo se llama en tu lista y encima de su chat.";
                case JAPANESE -> "一覧とチャットの上に表示される名前です。";
                case CHINESE -> "它在列表中和聊天上方显示的名称。";
            };
        }

        public String RENAME() {
            return switch (lang) {
                case ENGLISH -> "Rename";
                case GERMAN -> "Umbenennen";
                case SPANISH -> "Cambiar el nombre";
                case JAPANESE -> "名前を変更";
                case CHINESE -> "重命名";
            };
        }

        public String CANCEL() {
            return switch (lang) {
                case ENGLISH -> "Cancel";
                case GERMAN -> "Abbrechen";
                case SPANISH -> "Cancelar";
                case JAPANESE -> "キャンセル";
                case CHINESE -> "取消";
            };
        }

        public String SAVE() {
            return switch (lang) {
                case ENGLISH -> "Save";
                case GERMAN -> "Speichern";
                case SPANISH -> "Guardar";
                case JAPANESE -> "保存";
                case CHINESE -> "保存";
            };
        }

        public String NEW_CONVERSATION() {
            return switch (lang) {
                case ENGLISH -> "New conversation";
                case GERMAN -> "Neues Gespräch";
                case SPANISH -> "Nueva conversación";
                case JAPANESE -> "新しい会話";
                case CHINESE -> "新对话";
            };
        }

        public String START_AFRESH_WITH_IT_THIS_CONVERSATION_IS_KEPT(String genie) {
            return switch (lang) {
                case ENGLISH -> "Start afresh with " + genie + ", waking it first if it sleeps. This conversation is kept, to go back to.";
                case GERMAN -> "Mit " + genie + " neu anfangen und ihn vorher wecken, falls er schläft. Dieses Gespräch bleibt erhalten, du kannst später dorthin zurück.";
                case SPANISH -> "Empezar de cero con " + genie + ", despertándolo antes si duerme. Esta conversación se conserva para poder volver a ella.";
                case JAPANESE -> genie + " と新しく始めます。眠っていれば先に起こします。この会話は残るので、あとで戻れます。";
                case CHINESE -> "与 " + genie + " 重新开始，如果它在休眠会先唤醒它。此对话会保留，可以随时回来。";
            };
        }

        public String START_A_NEW_CONVERSATION() {
            return switch (lang) {
                case ENGLISH -> "Start a new conversation";
                case GERMAN -> "Neues Gespräch beginnen";
                case SPANISH -> "Empezar una conversación nueva";
                case JAPANESE -> "新しい会話を始める";
                case CHINESE -> "开始新对话";
            };
        }

        public String SAVE_NOW() {
            return switch (lang) {
                case ENGLISH -> "Save now";
                case GERMAN -> "Jetzt sichern";
                case SPANISH -> "Guardar ahora";
                case JAPANESE -> "今すぐ保存";
                case CHINESE -> "立即保存";
            };
        }

        public String SAVE_ITS_HOME_AS_IT_IS_NOW(String genie) {
            return switch (lang) {
                case ENGLISH -> "Save " + genie + "'s home as it is now: a moment in its history to go back to.";
                case GERMAN -> "Das Zuhause von " + genie + " so sichern, wie es jetzt ist: ein Stand im Verlauf, zu dem du zurückkehren kannst.";
                case SPANISH -> "Guardar el hogar de " + genie + " tal como está ahora: un momento de su historial al que volver.";
                case JAPANESE -> genie + " のホームを今の状態で保存します。履歴に、あとで戻れる時点が加わります。";
                case CHINESE -> "按当前状态保存 " + genie + " 的主目录：在历史中留下一个可以回到的保存点。";
            };
        }

        public String SAVE_NOW_ELLIPSIS() {
            return switch (lang) {
                case ENGLISH -> "Save now…";
                case GERMAN -> "Jetzt sichern…";
                case SPANISH -> "Guardar ahora…";
                case JAPANESE -> "今すぐ保存…";
                case CHINESE -> "立即保存…";
            };
        }

        public String SLEEP() {
            return switch (lang) {
                case ENGLISH -> "Sleep";
                case GERMAN -> "Schlafen legen";
                case SPANISH -> "Dormir";
                case JAPANESE -> "眠らせる";
                case CHINESE -> "休眠";
            };
        }

        public String WAKE() {
            return switch (lang) {
                case ENGLISH -> "Wake";
                case GERMAN -> "Wecken";
                case SPANISH -> "Despertar";
                case JAPANESE -> "起こす";
                case CHINESE -> "唤醒";
            };
        }

        public String IS_IN_THE_LAMP_WAKING_IT_STARTS_ITS_SANDBOX(String genie) {
            return switch (lang) {
                case ENGLISH -> genie + " is in the lamp. Waking it starts its sandbox, with its home as it left it.";
                case GERMAN -> genie + " ist in der Lampe. Wecken startet seine Sandbox, mit seinem Zuhause, wie er es verlassen hat.";
                case SPANISH -> genie + " está en la lámpara. Al despertarlo se inicia su sandbox, con su hogar tal como lo dejó.";
                case JAPANESE -> genie + " はランプの中にいます。起こすとサンドボックスが起動し、ホームは前回のままです。";
                case CHINESE -> genie + " 在神灯里。唤醒它会启动它的沙箱，主目录保持它离开时的样子。";
            };
        }

        public String IS_WAKING(String genie) {
            return switch (lang) {
                case ENGLISH -> genie + " is waking…";
                case GERMAN -> genie + " wacht auf…";
                case SPANISH -> genie + " se está despertando…";
                case JAPANESE -> genie + " が起きようとしています…";
                case CHINESE -> genie + " 正在醒来…";
            };
        }

        public String DID_NOT_WAKE_ITS_HOME_IS_KEPT(String genie) {
            return switch (lang) {
                case ENGLISH -> genie + " did not wake. Its home is kept, so it can try again.";
                case GERMAN -> genie + " ist nicht aufgewacht. Sein Zuhause bleibt erhalten, ein neuer Versuch ist also möglich.";
                case SPANISH -> genie + " no despertó. Su hogar se conserva, así que puede volver a intentarlo.";
                case JAPANESE -> genie + " は起きませんでした。ホームは残っているので、もう一度試せます。";
                case CHINESE -> genie + " 没有醒来。它的主目录仍然保留，可以再试一次。";
            };
        }

        public String END_ITS_SANDBOX_FOR_WHOEVER_LIT_IT_TOO(String genie) {
            return switch (lang) {
                case ENGLISH -> "End " + genie + "'s sandbox, for whoever lit it with `oillamp at` too. Its home and this conversation are kept.";
                case GERMAN -> "Die Sandbox von " + genie + " beenden, auch für den, der sie mit `oillamp at` entzündet hat. Sein Zuhause und dieses Gespräch bleiben erhalten.";
                case SPANISH -> "Cerrar el sandbox de " + genie + ", también para quien lo encendió con `oillamp at`. Su hogar y esta conversación se conservan.";
                case JAPANESE -> genie + " のサンドボックスを終了します。`oillamp at` で点灯した人にとっても終了します。ホームとこの会話は残ります。";
                case CHINESE -> "结束 " + genie + " 的沙箱，对用 `oillamp at` 点亮它的人也同样结束。它的主目录和此对话都会保留。";
            };
        }

        public String END_ITS_SANDBOX(String genie) {
            return switch (lang) {
                case ENGLISH -> "End " + genie + "'s sandbox. Its home and this conversation are kept.";
                case GERMAN -> "Die Sandbox von " + genie + " beenden. Sein Zuhause und dieses Gespräch bleiben erhalten.";
                case SPANISH -> "Cerrar el sandbox de " + genie + ". Su hogar y esta conversación se conservan.";
                case JAPANESE -> genie + " のサンドボックスを終了します。ホームとこの会話は残ります。";
                case CHINESE -> "结束 " + genie + " 的沙箱。它的主目录和此对话都会保留。";
            };
        }

        public String WAKING() {
            return switch (lang) {
                case ENGLISH -> "Waking…";
                case GERMAN -> "Wird geweckt…";
                case SPANISH -> "Despertando…";
                case JAPANESE -> "起こしています…";
                case CHINESE -> "正在唤醒…";
            };
        }

        public String TRY_WAKING_AGAIN() {
            return switch (lang) {
                case ENGLISH -> "Try waking again";
                case GERMAN -> "Noch einmal wecken";
                case SPANISH -> "Intentar despertarlo otra vez";
                case JAPANESE -> "もう一度起こす";
                case CHINESE -> "再次尝试唤醒";
            };
        }

        public String FOLDERS() {
            return switch (lang) {
                case ENGLISH -> "Folders";
                case GERMAN -> "Ordner";
                case SPANISH -> "Carpetas";
                case JAPANESE -> "フォルダー";
                case CHINESE -> "文件夹";
            };
        }

        public String REMOVE() {
            return switch (lang) {
                case ENGLISH -> "Remove";
                case GERMAN -> "Entfernen";
                case SPANISH -> "Quitar";
                case JAPANESE -> "外す";
                case CHINESE -> "移除";
            };
        }

        public String DELETE() {
            return switch (lang) {
                case ENGLISH -> "Delete";
                case GERMAN -> "Löschen";
                case SPANISH -> "Eliminar";
                case JAPANESE -> "削除";
                case CHINESE -> "删除";
            };
        }

        public String REMOVE_FROM_GENIES_ITS_LAMP_WAS_MADE_ELSEWHERE(String genie) {
            return switch (lang) {
                case ENGLISH ->
                    "Remove " + genie + " from Genies. Its lamp was made elsewhere, with `oillamp at`, and stays "
                    + "where it is, as it is, running if it runs. You are asked first.";
                case GERMAN -> genie + " aus Genies entfernen. Seine Lampe wurde anderswo mit `oillamp at` angelegt und bleibt, wo und wie sie ist, und läuft weiter, falls sie läuft. Du wirst vorher gefragt.";
                case SPANISH -> "Quitar a " + genie + " de Genies. Su lámpara se creó en otro sitio, con `oillamp at`, y se queda donde está, tal como está, y sigue en marcha si lo estaba. Antes se te pregunta.";
                case JAPANESE -> genie + " を Genies から外します。このランプはほかの場所で `oillamp at` によって作られたもので、そのまま残り、動いていれば動き続けます。実行前に確認があります。";
                case CHINESE -> "从 Genies 中移除 " + genie + "。它的神灯是在别处用 `oillamp at` 创建的，会原样保留，如果正在运行也会继续运行。操作前会先询问你。";
            };
        }

        public String DELETE_FOR_GOOD_YOU_ARE_ASKED_FIRST(String genie) {
            return switch (lang) {
                case ENGLISH ->
                    "Delete " + genie + " for good: its home, everything it made and your "
                    + "conversations with it. You are asked first.";
                case GERMAN -> genie + " endgültig löschen: sein Zuhause, alles, was er gemacht hat, und deine Gespräche mit ihm. Du wirst vorher gefragt.";
                case SPANISH -> "Eliminar a " + genie + " para siempre: su hogar, todo lo que ha creado y tus conversaciones con él. Antes se te pregunta.";
                case JAPANESE -> genie + " を完全に削除します。ホーム、作ったものすべて、このジーニーとの会話が対象です。実行前に確認があります。";
                case CHINESE -> "永久删除 " + genie + "：包括它的主目录、它创建的一切以及你与它的对话。操作前会先询问你。";
            };
        }

        public String REMOVE_FROM_GENIES_ELLIPSIS() {
            return switch (lang) {
                case ENGLISH -> "Remove from Genies…";
                case GERMAN -> "Aus Genies entfernen…";
                case SPANISH -> "Quitar de Genies…";
                case JAPANESE -> "Genies から外す…";
                case CHINESE -> "从 Genies 中移除…";
            };
        }

        public String DELETE_GENIE_ELLIPSIS(String genie) {
            return switch (lang) {
                case ENGLISH -> "Delete " + genie + "…";
                case GERMAN -> genie + " löschen…";
                case SPANISH -> "Eliminar a " + genie + "…";
                case JAPANESE -> genie + " を削除…";
                case CHINESE -> "删除 " + genie + "…";
            };
        }
    }

    /// The words of a genie's home on its Actions page, and of linking its folders into the
    /// user's.
    public record HomeFolders(Lang lang) {
        public String HAS_NO_HOME_YET(String genie) {
            return switch (lang) {
                case ENGLISH -> genie + " has no home yet. It gets one as it first wakes.";
                case GERMAN -> genie + " hat noch kein Zuhause. Er bekommt eins, wenn er zum ersten Mal aufwacht.";
                case SPANISH -> genie + " aún no tiene hogar. Lo tendrá la primera vez que despierte.";
                case JAPANESE -> genie + " にはまだホームがありません。初めて起きたときに作られます。";
                case CHINESE -> genie + " 还没有主目录。它第一次醒来时会创建一个。";
            };
        }

        public String ITS_HOME_IS_A_FOLDER_ON_THIS_COMPUTER_LINK_ONE_OF_ITS_FOLDERS_INTO_YOURS(String genie) {
            return switch (lang) {
                case ENGLISH ->
                    genie + "'s home is a folder on this computer. Link one of its folders into yours, to find what "
                    + genie + " made in your file manager. A link is no copy: a change on either side is one on both.";
                case GERMAN -> "Das Zuhause von " + genie + " ist ein Ordner auf diesem Rechner. Verknüpfe einen seiner Ordner mit einem von deinen, dann findest du in deinem Dateimanager, was " + genie + " gemacht hat. Eine Verknüpfung ist keine Kopie: Eine Änderung auf der einen Seite gilt für beide.";
                case SPANISH -> "El hogar de " + genie + " es una carpeta de este equipo. Enlaza una de sus carpetas en las tuyas para encontrar en tu gestor de archivos lo que ha hecho " + genie + ". Un enlace no es una copia: un cambio en un lado lo es en los dos.";
                case JAPANESE -> genie + " のホームは、このコンピューター上のフォルダーです。そのフォルダーの一つをあなたのフォルダーにリンクすれば、" + genie + " が作ったものをファイルマネージャーで見られます。リンクはコピーではないので、どちらで変更しても両方に反映されます。";
                case CHINESE -> genie + " 的主目录是这台电脑上的一个文件夹。把其中某个文件夹链接到你的文件夹里，就能在文件管理器中找到 " + genie + " 创建的内容。链接不是副本：在任一侧修改，两边都会同步变化。";
            };
        }

        public String ON_THIS_COMPUTER() {
            return switch (lang) {
                case ENGLISH -> "ON THIS COMPUTER";
                case GERMAN -> "AUF DIESEM RECHNER";
                case SPANISH -> "EN ESTE EQUIPO";
                case JAPANESE -> "このコンピューター上の場所";
                case CHINESE -> "在这台电脑上";
            };
        }

        public String OPEN_IN_YOUR_FILE_MANAGER() {
            return switch (lang) {
                case ENGLISH -> "Open in your file manager";
                case GERMAN -> "Im Dateimanager öffnen";
                case SPANISH -> "Abrir en tu gestor de archivos";
                case JAPANESE -> "ファイルマネージャーで開く";
                case CHINESE -> "在文件管理器中打开";
            };
        }

        public String THE_LINKS_NAME() {
            return switch (lang) {
                case ENGLISH -> "THE LINK'S NAME";
                case GERMAN -> "NAME DER VERKNÜPFUNG";
                case SPANISH -> "NOMBRE DEL ENLACE";
                case JAPANESE -> "リンクの名前";
                case CHINESE -> "链接名称";
            };
        }

        public String LINK_INTO(String folder) {
            return switch (lang) {
                case ENGLISH -> "Link into " + folder;
                case GERMAN -> "In " + folder + " verknüpfen";
                case SPANISH -> "Enlazar en " + folder;
                case JAPANESE -> folder + " にリンク";
                case CHINESE -> "链接到 " + folder;
            };
        }

        public String LINK_INTO_ELLIPSIS() {
            return switch (lang) {
                case ENGLISH -> "Link into…";
                case GERMAN -> "Verknüpfen in…";
                case SPANISH -> "Enlazar en…";
                case JAPANESE -> "リンク先を選択…";
                case CHINESE -> "链接到…";
            };
        }

        public String OR_DRAG_A_FOLDER_FROM_THE_TREE_INTO_YOUR_FILE_MANAGER(String genie) {
            return switch (lang) {
                case ENGLISH ->
                    "Or drag a folder from the tree into your file manager. "
                    + "Deleting " + genie + " leaves its links behind, leading nowhere.";
                case GERMAN -> "Oder zieh einen Ordner aus dem Baum in deinen Dateimanager. Wird " + genie + " gelöscht, bleiben seine Verknüpfungen zurück und führen ins Leere.";
                case SPANISH -> "O arrastra una carpeta del árbol a tu gestor de archivos. Si eliminas a " + genie + ", sus enlaces se quedan, pero ya no llevan a ninguna parte.";
                case JAPANESE -> "ツリーからフォルダーをファイルマネージャーにドラッグしても構いません。" + genie + " を削除してもリンクは残りますが、リンク先はなくなります。";
                case CHINESE -> "也可以把树中的文件夹拖到文件管理器里。删除 " + genie + " 后，它的链接仍会留下，但会指向不存在的位置。";
            };
        }

        public String WHERE_TO_PUT_THE_LINK(String link) {
            return switch (lang) {
                case ENGLISH -> "Where to put the link \"" + link + "\"";
                case GERMAN -> "Wohin mit der Verknüpfung „" + link + "“?";
                case SPANISH -> "Dónde poner el enlace «" + link + "»";
                case JAPANESE -> "リンク「" + link + "」の置き場所";
                case CHINESE -> "选择链接“" + link + "”的位置";
            };
        }

        public String LINK_HERE() {
            return switch (lang) {
                case ENGLISH -> "Link here";
                case GERMAN -> "Hier verknüpfen";
                case SPANISH -> "Enlazar aquí";
                case JAPANESE -> "ここにリンク";
                case CHINESE -> "链接到这里";
            };
        }

        public String LINKED(String at) {
            return switch (lang) {
                case ENGLISH -> "Linked: " + at;
                case GERMAN -> "Verknüpft: " + at;
                case SPANISH -> "Enlazado: " + at;
                case JAPANESE -> "リンクしました：" + at;
                case CHINESE -> "已链接：" + at;
            };
        }

        public String IT_WAS_LINKED_ALREADY(String at) {
            return switch (lang) {
                case ENGLISH -> "It was linked already: " + at;
                case GERMAN -> "War schon verknüpft: " + at;
                case SPANISH -> "Ya estaba enlazado: " + at;
                case JAPANESE -> "すでにリンクされています：" + at;
                case CHINESE -> "早已链接：" + at;
            };
        }
    }

    /// The words of a genie's Host access page: whether it may reach this computer, and the
    /// rules a model judges its requests by.
    public record HostAccessPage(Lang lang) {
        public String HOST_ACCESS() {
            return switch (lang) {
                case ENGLISH -> "Host access";
                case GERMAN -> "Rechnerzugriff";
                case SPANISH -> "Acceso al equipo";
                case JAPANESE -> "ホストへのアクセス";
                case CHINESE -> "主机访问";
            };
        }

        public String MAY_ASK_TO_REACH_THIS_COMPUTER_A_MODEL_JUDGES_EACH_REQUEST(String genie) {
            return switch (lang) {
                case ENGLISH -> genie + " may ask to reach this computer. A model judges each request against these rules before anything happens.";
                case GERMAN -> genie + " darf darum bitten, auf diesen Rechner zuzugreifen. Ein Modell prüft jede Anfrage anhand dieser Regeln, bevor irgendetwas passiert.";
                case SPANISH -> genie + " puede pedir acceso a este equipo. Un modelo juzga cada petición según estas reglas antes de que pase nada.";
                case JAPANESE -> genie + " はこのコンピューターへのアクセスを求めることができます。何かが実行される前に、モデルが各リクエストをこのルールに照らして判断します。";
                case CHINESE -> genie + " 可以请求访问这台电脑。在执行任何操作之前，都会由一个模型按这些规则审核每个请求。";
            };
        }

        public String CANNOT_REACH_THIS_COMPUTER(String genie) {
            return switch (lang) {
                case ENGLISH -> genie + " cannot reach this computer.";
                case GERMAN -> genie + " kann nicht auf diesen Rechner zugreifen.";
                case SPANISH -> genie + " no puede acceder a este equipo.";
                case JAPANESE -> genie + " はこのコンピューターにアクセスできません。";
                case CHINESE -> genie + " 无法访问这台电脑。";
            };
        }

        public String READING_THE_HOST_ACCESS() {
            return switch (lang) {
                case ENGLISH -> "Reading the host access…";
                case GERMAN -> "Lese den Rechnerzugriff…";
                case SPANISH -> "Leyendo el acceso al equipo…";
                case JAPANESE -> "ホストへのアクセス設定を読み込み中…";
                case CHINESE -> "正在读取主机访问设置…";
            };
        }

        public String WAKE_IT_ONCE_FIRST_ITS_SETTINGS_ARE_KEPT_IN_ITS_LAMP(String genie) {
            return switch (lang) {
                case ENGLISH -> "Wake " + genie + " once first. Its settings are kept in its lamp, which it gets as it first wakes.";
                case GERMAN -> "Wecke " + genie + " zuerst einmal. Seine Einstellungen liegen in seiner Lampe, und die bekommt er beim ersten Aufwachen.";
                case SPANISH -> "Despierta primero a " + genie + " una vez. Sus ajustes se guardan en su lámpara, que recibe la primera vez que despierta.";
                case JAPANESE -> "まず一度 " + genie + " を起こしてください。設定はランプに保存されますが、ランプは初めて起きたときに作られます。";
                case CHINESE -> "请先唤醒 " + genie + " 一次。它的设置保存在它的神灯里，而神灯要在它第一次醒来时才会创建。";
            };
        }

        public String SEND_IT_TO_SLEEP_TO_CHANGE_THESE(String genie) {
            return switch (lang) {
                case ENGLISH -> "Send " + genie + " to sleep to change these. While it is awake, it keeps the settings it woke with.";
                case GERMAN -> "Leg " + genie + " schlafen, um das zu ändern. Solange er wach ist, behält er die Einstellungen, mit denen er aufgewacht ist.";
                case SPANISH -> "Duerme a " + genie + " para cambiar esto. Mientras está despierto, conserva los ajustes con los que despertó.";
                case JAPANESE -> "変更するには " + genie + " を眠らせてください。起きている間は、起きたときの設定のままです。";
                case CHINESE -> "要修改这些设置，请先让 " + genie + " 休眠。它醒着时会一直使用醒来时的设置。";
            };
        }

        public String MAY_IT_REACH_THIS_COMPUTER() {
            return switch (lang) {
                case ENGLISH -> "MAY IT REACH THIS COMPUTER?";
                case GERMAN -> "DARF ER AUF DIESEN RECHNER ZUGREIFEN?";
                case SPANISH -> "¿PUEDE ACCEDER A ESTE EQUIPO?";
                case JAPANESE -> "このコンピューターへのアクセスを許可しますか？";
                case CHINESE -> "是否允许它访问这台电脑？";
            };
        }

        public String EXPERIMENTAL_NEVER_TURN_THIS_ON_ON_A_COMPUTER_WITH_SENSITIVE_DATA() {
            return switch (lang) {
                case ENGLISH ->
                    "Experimental. Never turn this on on a computer with sensitive data. "
                    + "The model that judges reads these rules with every request, and nothing else.";
                case GERMAN -> "Experimentell. Schalte das nie auf einem Rechner mit sensiblen Daten ein. Das prüfende Modell liest bei jeder Anfrage diese Regeln, und nichts sonst.";
                case SPANISH -> "Experimental. Nunca lo actives en un equipo con datos sensibles. El modelo que juzga lee estas reglas con cada petición, y nada más.";
                case JAPANESE -> "試験的な機能です。機密データのあるコンピューターでは絶対にオンにしないでください。判断するモデルは、リクエストのたびにこのルールだけを読みます。";
                case CHINESE -> "实验性功能。切勿在存有敏感数据的电脑上开启。审核模型每次处理请求时只会阅读这些规则，别无其他。";
            };
        }

        public String FOLDERS_IT_MAY_READ() {
            return switch (lang) {
                case ENGLISH -> "FOLDERS IT MAY READ";
                case GERMAN -> "ORDNER, DIE ER LESEN DARF";
                case SPANISH -> "CARPETAS QUE PUEDE LEER";
                case JAPANESE -> "読み取りを許可するフォルダー";
                case CHINESE -> "允许读取的文件夹";
            };
        }

        public String ONE_PER_LINE_SUCH_AS_DOCUMENTS() {
            return switch (lang) {
                case ENGLISH -> "One per line, such as ~/Documents.";
                case GERMAN -> "Einer pro Zeile, etwa ~/Dokumente.";
                case SPANISH -> "Una por línea, como ~/Documentos.";
                case JAPANESE -> "1 行に 1 つずつ。例：~/Documents";
                case CHINESE -> "每行一个，例如 ~/Documents。";
            };
        }

        public String FOLDERS_IT_MAY_READ_AND_CHANGE() {
            return switch (lang) {
                case ENGLISH -> "FOLDERS IT MAY READ AND CHANGE";
                case GERMAN -> "ORDNER, DIE ER LESEN UND ÄNDERN DARF";
                case SPANISH -> "CARPETAS QUE PUEDE LEER Y CAMBIAR";
                case JAPANESE -> "読み取りと変更を許可するフォルダー";
                case CHINESE -> "允许读取和修改的文件夹";
            };
        }

        public String IT_MAY_WRITE_FILES_THERE_AND_COPY_WHAT_IT_MADE_THERE() {
            return switch (lang) {
                case ENGLISH -> "It may write files there, and copy what it made there.";
                case GERMAN -> "Dort darf er Dateien schreiben und hinkopieren, was er gemacht hat.";
                case SPANISH -> "Puede escribir archivos ahí y copiar ahí lo que ha creado.";
                case JAPANESE -> "そこにファイルを書き込んだり、作ったものをコピーしたりできます。";
                case CHINESE -> "它可以在那里写入文件，也可以把它创建的内容复制过去。";
            };
        }

        public String ANYTHING_ELSE_IN_YOUR_OWN_WORDS() {
            return switch (lang) {
                case ENGLISH -> "ANYTHING ELSE, IN YOUR OWN WORDS";
                case GERMAN -> "SONST NOCH ETWAS, IN DEINEN EIGENEN WORTEN";
                case SPANISH -> "CUALQUIER OTRA COSA, CON TUS PROPIAS PALABRAS";
                case JAPANESE -> "そのほかのルール（自由記述）";
                case CHINESE -> "其他要求（用你自己的话说）";
            };
        }

        public String SAY_WHAT_MUST_NOT_HAPPEN() {
            return switch (lang) {
                case ENGLISH -> "Say what must not happen, such as \"never open my tax returns\".";
                case GERMAN -> "Schreib, was nicht passieren darf, etwa „öffne nie meine Steuererklärungen“.";
                case SPANISH -> "Di lo que no debe pasar, como «nunca abras mis declaraciones de la renta».";
                case JAPANESE -> "してはいけないことを書いてください。例：「確定申告の書類は絶対に開かない」";
                case CHINESE -> "写下绝不能发生的事，例如“永远不要打开我的报税文件”。";
            };
        }

        public String UNDO_CHANGES() {
            return switch (lang) {
                case ENGLISH -> "Undo changes";
                case GERMAN -> "Änderungen verwerfen";
                case SPANISH -> "Deshacer los cambios";
                case JAPANESE -> "変更を元に戻す";
                case CHINESE -> "撤销更改";
            };
        }

        public String SAVE() {
            return switch (lang) {
                case ENGLISH -> "Save";
                case GERMAN -> "Speichern";
                case SPANISH -> "Guardar";
                case JAPANESE -> "保存";
                case CHINESE -> "保存";
            };
        }

        public String SAVING() {
            return switch (lang) {
                case ENGLISH -> "Saving…";
                case GERMAN -> "Speichere…";
                case SPANISH -> "Guardando…";
                case JAPANESE -> "保存中…";
                case CHINESE -> "正在保存…";
            };
        }

        public String WRITE_THESE_RULES_INTO_THE_GENIES_LAMP() {
            return switch (lang) {
                case ENGLISH -> "Write these rules into the genie's lamp";
                case GERMAN -> "Diese Regeln in die Lampe des Dschinns schreiben";
                case SPANISH -> "Escribir estas reglas en la lámpara del genio";
                case JAPANESE -> "これらのルールをジーニーのランプに書き込みます";
                case CHINESE -> "将这些规则写入精灵的神灯";
            };
        }

        public String IS_NOT_A_PATH_THE_MODEL_THAT_JUDGES_READS_IT_AS_WORDS(String folder) {
            return switch (lang) {
                case ENGLISH -> "\"" + folder + "\" is not a path. The model that judges reads it as words, so make sure they say what you mean.";
                case GERMAN -> "„" + folder + "“ ist kein Pfad. Das prüfende Modell liest es als Worte, also achte darauf, dass sie sagen, was du meinst.";
                case SPANISH -> "«" + folder + "» no es una ruta. El modelo que juzga lo lee como palabras, así que asegúrate de que digan lo que quieres decir.";
                case JAPANESE -> "「" + folder + "」はパスではありません。判断するモデルはこれを言葉として読むので、意図したとおりの意味になっているか確認してください。";
                case CHINESE -> "“" + folder + "”不是路径。审核模型会把它当作文字来理解，所以请确保它表达的是你的本意。";
            };
        }

        public String NOTHING_IS_LISTED_SO_THE_MODEL_THAT_JUDGES_WILL_REFUSE_EVERY_REQUEST() {
            return switch (lang) {
                case ENGLISH -> "Nothing is listed, so the model that judges will refuse every request.";
                case GERMAN -> "Hier steht nichts, also lehnt das prüfende Modell jede Anfrage ab.";
                case SPANISH -> "No hay nada en la lista, así que el modelo que juzga rechazará todas las peticiones.";
                case JAPANESE -> "何も指定されていないため、判断するモデルはすべてのリクエストを拒否します。";
                case CHINESE -> "这里什么都没有列出，因此审核模型会拒绝所有请求。";
            };
        }
    }

    /// The words of the settings every genie shares, and of the welcome above them while there
    /// are no genies.
    public record SettingsPage(Lang lang) {
        public String YOUR_GENIES_MODEL() {
            return switch (lang) {
                case ENGLISH -> "Your genies' model";
                case GERMAN -> "Das Modell deiner Dschinns";
                case SPANISH -> "El modelo de tus genios";
                case JAPANESE -> "ジーニーのモデル";
                case CHINESE -> "精灵使用的模型";
            };
        }

        public String SETTINGS() {
            return switch (lang) {
                case ENGLISH -> "Settings";
                case GERMAN -> "Einstellungen";
                case SPANISH -> "Ajustes";
                case JAPANESE -> "設定";
                case CHINESE -> "设置";
            };
        }

        public String WHERE_YOUR_GENIES_MODEL_RUNS_AND_HOW_THEY_REACH_IT() {
            return switch (lang) {
                case ENGLISH -> "Where your genies' model runs, and how they reach it.";
                case GERMAN -> "Wo das Modell deiner Dschinns läuft und wie sie es erreichen.";
                case SPANISH -> "Dónde funciona el modelo de tus genios y cómo llegan a él.";
                case JAPANESE -> "ジーニーのモデルがどこで動き、どうやって接続するか。";
                case CHINESE -> "精灵的模型在哪里运行，以及它们如何连接到模型。";
            };
        }

        public String GENIES_SETS_UP_A_MODEL_ON_THIS_COMPUTER_FOR_YOU_WITH_OLLAMA() {
            return switch (lang) {
                case ENGLISH -> "Genies sets up a model on this computer for you, with Ollama: free, and your conversations stay here.";
                case GERMAN -> "Genies richtet dir mit Ollama ein Modell auf diesem Rechner ein: kostenlos, und deine Gespräche bleiben hier.";
                case SPANISH -> "Genies te instala un modelo en este equipo con Ollama: es gratis y tus conversaciones no salen de aquí.";
                case JAPANESE -> "Genies が Ollama を使って、このコンピューターにモデルを用意します。無料で、会話が外に出ることもありません。";
                case CHINESE -> "Genies 会用 Ollama 在这台电脑上为你配置模型：免费，而且你的对话不会离开这台电脑。";
            };
        }

        public String ADVANCED_EDEN_AI_OR_A_MODEL_SERVER_OF_YOUR_OWN() {
            return switch (lang) {
                case ENGLISH -> "Advanced: Eden AI, or a model server of your own";
                case GERMAN -> "Erweitert: Eden AI oder ein eigener Modellserver";
                case SPANISH -> "Avanzado: Eden AI o un servidor de modelos propio";
                case JAPANESE -> "詳細設定：Eden AI または独自のモデルサーバー";
                case CHINESE -> "高级：Eden AI 或你自己的模型服务器";
            };
        }

        public String FOR_A_MODEL_ELSEWHERE_OR_A_MODEL_SERVER_OTHER_THAN_OLLAMA() {
            return switch (lang) {
                case ENGLISH -> "For a model elsewhere, or a model server on this computer other than Ollama";
                case GERMAN -> "Für ein Modell anderswo, oder einen anderen Modellserver als Ollama auf diesem Rechner";
                case SPANISH -> "Para un modelo en otro sitio, o un servidor de modelos en este equipo que no sea Ollama";
                case JAPANESE -> "ほかの場所のモデルや、このコンピューター上の Ollama 以外のモデルサーバーを使う場合";
                case CHINESE -> "适用于其他位置的模型，或这台电脑上 Ollama 以外的模型服务器";
            };
        }

        public String BACK_TO_THE_SIMPLE_WAY_OLLAMA_SET_UP_BY_GENIES() {
            return switch (lang) {
                case ENGLISH -> "Back to the simple way: Ollama, set up by Genies";
                case GERMAN -> "Zurück zum einfachen Weg: Ollama, eingerichtet von Genies";
                case SPANISH -> "Volver a lo sencillo: Ollama, instalado por Genies";
                case JAPANESE -> "かんたん設定に戻る：Genies が用意する Ollama";
                case CHINESE -> "回到简单方式：由 Genies 配置的 Ollama";
            };
        }

        public String THIS_COMPUTER() {
            return switch (lang) {
                case ENGLISH -> "This computer";
                case GERMAN -> "Dieser Rechner";
                case SPANISH -> "Este equipo";
                case JAPANESE -> "このコンピューター";
                case CHINESE -> "这台电脑";
            };
        }

        public String GENIES_IS_LOOKING_WHAT_THIS_COMPUTER_HAS() {
            return switch (lang) {
                case ENGLISH -> "Genies is looking what this computer has…";
                case GERMAN -> "Genies schaut, was dieser Rechner hat…";
                case SPANISH -> "Genies está mirando qué tiene este equipo…";
                case JAPANESE -> "Genies がこのコンピューターの性能を調べています…";
                case CHINESE -> "Genies 正在查看这台电脑的配置…";
            };
        }

        public String OLLAMA() {
            return switch (lang) {
                case ENGLISH -> "Ollama";
                case GERMAN -> "Ollama";
                case SPANISH -> "Ollama";
                case JAPANESE -> "Ollama";
                case CHINESE -> "Ollama";
            };
        }

        public String MODEL() {
            return switch (lang) {
                case ENGLISH -> "Model";
                case GERMAN -> "Modell";
                case SPANISH -> "Modelo";
                case JAPANESE -> "モデル";
                case CHINESE -> "模型";
            };
        }

        public String GENIES_SUGGESTS_THE_BEST_MODEL_THIS_COMPUTER_RUNS_WELL() {
            return switch (lang) {
                case ENGLISH ->
                    "Genies suggests the best model this computer runs well. Any other "
                    + "Ollama model that can use tools works too: ollama.com/search?c=tools lists them.";
                case GERMAN -> "Genies schlägt das beste Modell vor, das auf diesem Rechner gut läuft. Jedes andere Ollama-Modell, das Werkzeuge nutzen kann, geht auch: ollama.com/search?c=tools listet sie auf.";
                case SPANISH -> "Genies te sugiere el mejor modelo que funciona bien en este equipo. También sirve cualquier otro modelo de Ollama que sepa usar herramientas: ollama.com/search?c=tools los muestra.";
                case JAPANESE -> "Genies は、このコンピューターで快適に動く最適なモデルを提案します。ツールを使える Ollama のモデルなら、ほかのものでも使えます。一覧は ollama.com/search?c=tools にあります。";
                case CHINESE -> "Genies 会推荐这台电脑能流畅运行的最佳模型。其他支持工具调用的 Ollama 模型也可以使用，列表见 ollama.com/search?c=tools。";
            };
        }

        public String YOUR_GENIES_USE(String wanted) {
            return switch (lang) {
                case ENGLISH -> "Your genies use " + wanted + ".";
                case GERMAN -> "Deine Dschinns nutzen " + wanted + ".";
                case SPANISH -> "Tus genios usan " + wanted + ".";
                case JAPANESE -> "ジーニーは " + wanted + " を使っています。";
                case CHINESE -> "你的精灵正在使用 " + wanted + "。";
            };
        }

        public String STOP() {
            return switch (lang) {
                case ENGLISH -> "Stop";
                case GERMAN -> "Anhalten";
                case SPANISH -> "Detener";
                case JAPANESE -> "停止";
                case CHINESE -> "停止";
            };
        }

        public String STOP_SETTING_UP_A_MODELS_DOWNLOAD_GOES_ON_THE_NEXT_TIME() {
            return switch (lang) {
                case ENGLISH -> "Stop setting up. A model's download goes on from where it was the next time";
                case GERMAN -> "Einrichtung anhalten. Der Download eines Modells geht beim nächsten Mal dort weiter, wo er war";
                case SPANISH -> "Detener la instalación. La descarga de un modelo seguirá donde se quedó la próxima vez";
                case JAPANESE -> "セットアップを止めます。モデルのダウンロードは、次回中断したところから再開します";
                case CHINESE -> "停止配置。模型下载下次会从中断处继续";
            };
        }

        public String UNTIL_THEN_YOUR_GENIES_USE_EDEN_AI() {
            return switch (lang) {
                case ENGLISH -> "Until then, your genies use Eden AI.";
                case GERMAN -> "Bis dahin nutzen deine Dschinns Eden AI.";
                case SPANISH -> "Hasta entonces, tus genios usan Eden AI.";
                case JAPANESE -> "それまでは、ジーニーは Eden AI を使います。";
                case CHINESE -> "在此之前，你的精灵会使用 Eden AI。";
            };
        }

        public String UNTIL_THEN_YOUR_GENIES_USE_THE_MODEL_SERVER_AT(String at) {
            return switch (lang) {
                case ENGLISH -> "Until then, your genies use the model server at " + at + ".";
                case GERMAN -> "Bis dahin nutzen deine Dschinns den Modellserver unter " + at + ".";
                case SPANISH -> "Hasta entonces, tus genios usan el servidor de modelos de " + at + ".";
                case JAPANESE -> "それまでは、ジーニーは " + at + " のモデルサーバーを使います。";
                case CHINESE -> "在此之前，你的精灵会使用位于 " + at + " 的模型服务器。";
            };
        }

        public String THE_MODEL_RUNS() {
            return switch (lang) {
                case ENGLISH -> "The model runs";
                case GERMAN -> "Das Modell läuft";
                case SPANISH -> "El modelo funciona";
                case JAPANESE -> "モデルの実行場所";
                case CHINESE -> "模型运行于";
            };
        }

        public String AT_EDEN_AI() {
            return switch (lang) {
                case ENGLISH -> "at Eden AI";
                case GERMAN -> "bei Eden AI";
                case SPANISH -> "en Eden AI";
                case JAPANESE -> "Eden AI";
                case CHINESE -> "Eden AI";
            };
        }

        public String IN_THE_EU_WITH_YOUR_EDEN_AI_KEY() {
            return switch (lang) {
                case ENGLISH -> "In the EU, with your Eden AI key.";
                case GERMAN -> "In der EU, mit deinem Eden-AI-Schlüssel.";
                case SPANISH -> "En la UE, con tu clave de Eden AI.";
                case JAPANESE -> "EU 内で、あなたの Eden AI キーを使います。";
                case CHINESE -> "在欧盟境内，使用你的 Eden AI 密钥。";
            };
        }

        public String ON_A_SERVER_ELSEWHERE() {
            return switch (lang) {
                case ENGLISH -> "on a server elsewhere";
                case GERMAN -> "auf einem Server anderswo";
                case SPANISH -> "en un servidor de otro sitio";
                case JAPANESE -> "ほかの場所のサーバー";
                case CHINESE -> "其他位置的服务器";
            };
        }

        public String A_MODEL_SERVER_OF_YOUR_OWN_ON_ANOTHER_MACHINE() {
            return switch (lang) {
                case ENGLISH -> "A model server of your own on another machine, such as Ollama behind a proxy that asks for a key.";
                case GERMAN -> "Ein eigener Modellserver auf einem anderen Rechner, etwa Ollama hinter einem Proxy, der einen Schlüssel verlangt.";
                case SPANISH -> "Un servidor de modelos propio en otra máquina, como Ollama detrás de un proxy que pide una clave.";
                case JAPANESE -> "別のマシンにある独自のモデルサーバー。たとえば、キーを求めるプロキシの背後にある Ollama など。";
                case CHINESE -> "你自己在另一台机器上的模型服务器，例如部署在需要密钥的代理之后的 Ollama。";
            };
        }

        public String ON_THIS_COMPUTER() {
            return switch (lang) {
                case ENGLISH -> "on this computer";
                case GERMAN -> "auf diesem Rechner";
                case SPANISH -> "en este equipo";
                case JAPANESE -> "このコンピューター";
                case CHINESE -> "这台电脑";
            };
        }

        public String A_MODEL_SERVER_SUCH_AS_OLLAMA_LM_STUDIO_OR_LLAMA_CPP() {
            return switch (lang) {
                case ENGLISH -> "A model server such as Ollama, LM Studio or llama.cpp.";
                case GERMAN -> "Ein Modellserver wie Ollama, LM Studio oder llama.cpp.";
                case SPANISH -> "Un servidor de modelos como Ollama, LM Studio o llama.cpp.";
                case JAPANESE -> "Ollama、LM Studio、llama.cpp などのモデルサーバー。";
                case CHINESE -> "Ollama、LM Studio 或 llama.cpp 等模型服务器。";
            };
        }

        public String KEY() {
            return switch (lang) {
                case ENGLISH -> "Key";
                case GERMAN -> "Schlüssel";
                case SPANISH -> "Clave";
                case JAPANESE -> "キー";
                case CHINESE -> "密钥";
            };
        }

        public String USE(String variable) {
            return switch (lang) {
                case ENGLISH -> "Use " + variable;
                case GERMAN -> variable + " verwenden";
                case SPANISH -> "Usar " + variable;
                case JAPANESE -> variable + " を使う";
                case CHINESE -> "使用 " + variable;
            };
        }

        public String FOUND_WHERE_GENIES_WAS_STARTED() {
            return switch (lang) {
                case ENGLISH -> "Found where Genies was started.";
                case GERMAN -> "Dort gefunden, wo Genies gestartet wurde.";
                case SPANISH -> "Encontrada donde se inició Genies.";
                case JAPANESE -> "Genies を起動した環境で見つかりました。";
                case CHINESE -> "已在启动 Genies 的环境中找到。";
            };
        }

        public String NOT_SET_WHERE_GENIES_WAS_STARTED() {
            return switch (lang) {
                case ENGLISH -> "Not set where Genies was started.";
                case GERMAN -> "Dort nicht gesetzt, wo Genies gestartet wurde.";
                case SPANISH -> "No está definida donde se inició Genies.";
                case JAPANESE -> "Genies を起動した環境では設定されていません。";
                case CHINESE -> "启动 Genies 的环境中未设置。";
            };
        }

        public String USE_THIS_KEY() {
            return switch (lang) {
                case ENGLISH -> "Use this key:";
                case GERMAN -> "Diesen Schlüssel verwenden:";
                case SPANISH -> "Usar esta clave:";
                case JAPANESE -> "このキーを使う：";
                case CHINESE -> "使用此密钥：";
            };
        }

        public String THE_KEY_STAYS_ON_THIS_COMPUTER_GENIES_NEVER_SEE_IT() {
            return switch (lang) {
                case ENGLISH ->
                    "The key stays on this computer. Genies never see it: oillamp adds it to "
                    + "their requests as they leave the sandbox. An entered key is kept in a file only you can read.";
                case GERMAN -> "Der Schlüssel bleibt auf diesem Rechner. Die Dschinns sehen ihn nie: oillamp fügt ihn ihren Anfragen erst hinzu, wenn sie die Sandbox verlassen. Ein eingegebener Schlüssel liegt in einer Datei, die nur du lesen kannst.";
                case SPANISH -> "La clave se queda en este equipo. Los genios nunca la ven: oillamp la añade a sus peticiones cuando salen del sandbox. Una clave introducida se guarda en un archivo que solo tú puedes leer.";
                case JAPANESE -> "キーはこのコンピューターから出ません。ジーニーがキーを見ることはなく、リクエストがサンドボックスを出るときに oillamp が付け加えます。入力したキーは、あなただけが読めるファイルに保存されます。";
                case CHINESE -> "密钥只保存在这台电脑上。精灵永远看不到它：请求离开沙箱时，才由 oillamp 加上密钥。你输入的密钥会保存在只有你能读取的文件中。";
            };
        }

        public String ADDRESS() {
            return switch (lang) {
                case ENGLISH -> "Address";
                case GERMAN -> "Adresse";
                case SPANISH -> "Dirección";
                case JAPANESE -> "アドレス";
                case CHINESE -> "地址";
            };
        }

        public String WHERE_THE_SERVERS_API_IS_IT_MUST_BE_HTTPS() {
            return switch (lang) {
                case ENGLISH ->
                    "Where the server's OpenAI-style API is, such as "
                    + "https://ollama.example.com/v1. It must be https://, because the key goes with every request.";
                case GERMAN -> "Wo die OpenAI-artige API des Servers liegt, etwa https://ollama.example.com/v1. Sie muss mit https:// beginnen, weil der Schlüssel bei jeder Anfrage mitgeht.";
                case SPANISH -> "Dónde está la API al estilo de OpenAI del servidor, como https://ollama.example.com/v1. Debe ser https://, porque la clave viaja con cada petición.";
                case JAPANESE -> "サーバーの OpenAI 互換 API の場所。例：https://ollama.example.com/v1。キーがリクエストのたびに送られるため、https:// でなければなりません。";
                case CHINESE -> "服务器的 OpenAI 兼容 API 地址，例如 https://ollama.example.com/v1。由于每次请求都会附带密钥，必须使用 https://。";
            };
        }

        public String LEAVE_IT_EMPTY_IF_THE_SERVER_ASKS_FOR_NONE() {
            return switch (lang) {
                case ENGLISH -> "Leave it empty if the server asks for none.";
                case GERMAN -> "Leer lassen, wenn der Server keinen verlangt.";
                case SPANISH -> "Déjala vacía si el servidor no pide ninguna.";
                case JAPANESE -> "サーバーがキーを求めない場合は空欄のままにしてください。";
                case CHINESE -> "如果服务器不需要密钥，请留空。";
            };
        }

        public String WHERE_THE_SERVERS_API_IS_ON_THIS_COMPUTER_NO_KEY_IS_NEEDED() {
            return switch (lang) {
                case ENGLISH ->
                    "Where the server's OpenAI-style API is: http://127.0.0.1:11434/v1 for Ollama, "
                    + "http://127.0.0.1:1234/v1 for LM Studio, http://127.0.0.1:8080/v1 for llama.cpp. No key is needed.";
                case GERMAN -> "Wo die OpenAI-artige API des Servers liegt: http://127.0.0.1:11434/v1 für Ollama, http://127.0.0.1:1234/v1 für LM Studio, http://127.0.0.1:8080/v1 für llama.cpp. Ein Schlüssel ist nicht nötig.";
                case SPANISH -> "Dónde está la API al estilo de OpenAI del servidor: http://127.0.0.1:11434/v1 para Ollama, http://127.0.0.1:1234/v1 para LM Studio, http://127.0.0.1:8080/v1 para llama.cpp. No hace falta clave.";
                case JAPANESE -> "サーバーの OpenAI 互換 API の場所。Ollama なら http://127.0.0.1:11434/v1、LM Studio なら http://127.0.0.1:1234/v1、llama.cpp なら http://127.0.0.1:8080/v1 です。キーは不要です。";
                case CHINESE -> "服务器的 OpenAI 兼容 API 地址：Ollama 为 http://127.0.0.1:11434/v1，LM Studio 为 http://127.0.0.1:1234/v1，llama.cpp 为 http://127.0.0.1:8080/v1。无需密钥。";
            };
        }

        public String LOOK_UP() {
            return switch (lang) {
                case ENGLISH -> "Look up";
                case GERMAN -> "Nachsehen";
                case SPANISH -> "Consultar";
                case JAPANESE -> "一覧を取得";
                case CHINESE -> "查询";
            };
        }

        public String ASK_THE_SERVICE_WHICH_MODELS_IT_OFFERS() {
            return switch (lang) {
                case ENGLISH -> "Ask the service which models it offers";
                case GERMAN -> "Den Dienst fragen, welche Modelle er anbietet";
                case SPANISH -> "Preguntar al servicio qué modelos ofrece";
                case JAPANESE -> "提供されているモデルをサービスに問い合わせます";
                case CHINESE -> "向服务查询它提供哪些模型";
            };
        }

        public String CHANGES_TAKE_EFFECT_WHEN_A_GENIE_WAKES() {
            return switch (lang) {
                case ENGLISH -> "Changes take effect when a genie wakes.";
                case GERMAN -> "Änderungen wirken, sobald ein Dschinn aufwacht.";
                case SPANISH -> "Los cambios se aplican cuando un genio despierta.";
                case JAPANESE -> "変更はジーニーが起きたときに反映されます。";
                case CHINESE -> "更改会在精灵醒来时生效。";
            };
        }

        public String YOUR_GENIES_CAN_REACH_THEIR_MODEL() {
            return switch (lang) {
                case ENGLISH -> "Your genies can reach their model.";
                case GERMAN -> "Deine Dschinns erreichen ihr Modell.";
                case SPANISH -> "Tus genios pueden llegar a su modelo.";
                case JAPANESE -> "ジーニーはモデルに接続できます。";
                case CHINESE -> "你的精灵可以连接到它们的模型。";
            };
        }

        public String DONE() {
            return switch (lang) {
                case ENGLISH -> "Done";
                case GERMAN -> "Fertig";
                case SPANISH -> "Listo";
                case JAPANESE -> "完了";
                case CHINESE -> "完成";
            };
        }

        public String YOUR_FIRST_GENIE() {
            return switch (lang) {
                case ENGLISH -> "Your first genie";
                case GERMAN -> "Dein erster Dschinn";
                case SPANISH -> "Tu primer genio";
                case JAPANESE -> "最初のジーニー";
                case CHINESE -> "你的第一个精灵";
            };
        }

        public String A_GENIE_WITH_A_SANDBOXED_DESKTOP_OF_ITS_OWN_THAT_WAKES_AT_ONCE() {
            return switch (lang) {
                case ENGLISH -> "A genie, with a sandboxed desktop of its own, that wakes at once";
                case GERMAN -> "Ein Dschinn mit eigenem Desktop in einer Sandbox, der sofort aufwacht";
                case SPANISH -> "Un genio con su propio escritorio aislado, que despierta enseguida";
                case JAPANESE -> "サンドボックス内に専用のデスクトップを持ち、すぐに起きるジーニー";
                case CHINESE -> "拥有自己沙箱桌面的精灵，创建后立即唤醒";
            };
        }

        public String PERSONALIZATION() {
            return switch (lang) {
                case ENGLISH -> "Personalization";
                case GERMAN -> "Personalisierung";
                case SPANISH -> "Personalización";
                case JAPANESE -> "パーソナライズ";
                case CHINESE -> "个性化";
            };
        }

        public String ITS_LIGHT_AND_ITS_LANGUAGE() {
            return switch (lang) {
                case ENGLISH -> "Its light, and its language.";
                case GERMAN -> "Licht und Sprache der Lampe.";
                case SPANISH -> "Luz e idioma de la lámpara.";
                case JAPANESE -> "ランプの明かりと言葉。";
                case CHINESE -> "神灯的光与语言。";
            };
        }

        public String THE_LANGUAGE_GENIES_SPEAKS() {
            return switch (lang) {
                case ENGLISH -> "The language Genies speaks";
                case GERMAN -> "Die Sprache, in der Genies mit dir spricht";
                case SPANISH -> "El idioma en el que te habla Genies";
                case JAPANESE -> "Genies の表示言語";
                case CHINESE -> "Genies 的界面语言";
            };
        }

        public String DARK() {
            return switch (lang) {
                case ENGLISH -> "Dark";
                case GERMAN -> "Dunkel";
                case SPANISH -> "Oscuro";
                case JAPANESE -> "ダーク";
                case CHINESE -> "深色";
            };
        }

        public String LIGHT() {
            return switch (lang) {
                case ENGLISH -> "Light";
                case GERMAN -> "Hell";
                case SPANISH -> "Claro";
                case JAPANESE -> "ライト";
                case CHINESE -> "浅色";
            };
        }

        public String WELCOME_TO_GENIES() {
            return switch (lang) {
                case ENGLISH -> "Welcome to Genies";
                case GERMAN -> "Willkommen bei Genies";
                case SPANISH -> "Te damos la bienvenida a Genies";
                case JAPANESE -> "Genies へようこそ";
                case CHINESE -> "欢迎使用 Genies";
            };
        }

        public String A_GENIE_IS_AN_AI_HELPER_WITH_A_COMPUTER_OF_ITS_OWN() {
            return switch (lang) {
                case ENGLISH ->
                    "A genie is an AI helper with a computer of its own: a Linux desktop in a sandbox, "
                    + "where it can browse the web, run programs and make files, without ever touching yours.";
                case GERMAN -> "Ein Dschinn ist ein KI-Helfer mit einem eigenen Computer: einem Linux-Desktop in einer Sandbox, wo er im Web surfen, Programme ausführen und Dateien anlegen kann, ohne je deinen anzurühren.";
                case SPANISH -> "Un genio es un asistente de IA con su propio ordenador: un escritorio Linux dentro de un sandbox, donde puede navegar por la web, ejecutar programas y crear archivos sin tocar nunca el tuyo.";
                case JAPANESE -> "ジーニーは、自分専用のコンピューターを持つ AI アシスタントです。サンドボックス内の Linux デスクトップで、Web を見たり、プログラムを動かしたり、ファイルを作ったりしますが、あなたのコンピューターには一切触れません。";
                case CHINESE -> "精灵是拥有自己电脑的 AI 助手：它在沙箱中的 Linux 桌面上浏览网页、运行程序、创建文件，绝不会碰你的电脑。";
            };
        }

        public String GENIES_FOUND_AN_EDEN_AI_KEY_WHERE_IT_WAS_STARTED() {
            return switch (lang) {
                case ENGLISH ->
                    "Genies found an Eden AI key where it was started, so your genies can think at Eden AI right away. "
                    + "Or have Genies set up a model on this computer instead: the simple way, below.";
                case GERMAN -> "Genies hat dort, wo es gestartet wurde, einen Eden-AI-Schlüssel gefunden, deine Dschinns können also sofort bei Eden AI denken. Oder lass Genies stattdessen ein Modell auf diesem Rechner einrichten: der einfache Weg, unten.";
                case SPANISH -> "Genies encontró una clave de Eden AI donde se inició, así que tus genios pueden pensar en Eden AI desde ya. O deja que Genies instale un modelo en este equipo: la forma sencilla, más abajo.";
                case JAPANESE -> "Genies を起動した環境で Eden AI のキーが見つかったので、ジーニーはすぐに Eden AI で考えられます。代わりに、このコンピューターにモデルを用意することもできます。下のかんたん設定をご覧ください。";
                case CHINESE -> "Genies 在启动环境中找到了 Eden AI 密钥，所以你的精灵现在就能借助 Eden AI 思考。也可以让 Genies 在这台电脑上配置模型：见下方的简单方式。";
            };
        }

        public String TO_THINK_GENIES_NEED_A_MODEL_GENIES_CAN_SET_ONE_UP_FOR_YOU() {
            return switch (lang) {
                case ENGLISH -> "To think, genies need a model. Genies can set one up for you, right here on this computer.";
                case GERMAN -> "Zum Denken brauchen Dschinns ein Modell. Genies kann dir eins einrichten, direkt hier auf diesem Rechner.";
                case SPANISH -> "Para pensar, los genios necesitan un modelo. Genies puede instalarte uno aquí mismo, en este equipo.";
                case JAPANESE -> "ジーニーが考えるにはモデルが必要です。Genies が、このコンピューターにモデルを用意できます。";
                case CHINESE -> "精灵需要模型才能思考。Genies 可以直接在这台电脑上为你配置一个。";
            };
        }

        public String GENIES_THAT_ARE_AWAKE_ANSWER_WITH_A_NEW_MODEL_FROM_THEIR_NEXT_MESSAGE_ON() {
            return switch (lang) {
                case ENGLISH ->
                    "Genies that are awake answer with a new model from their next message on. "
                    + "A new place, address or key reaches them when they next wake, and so does "
                    + "everything for a genie that kept running while Genies was closed.";
                case GERMAN -> "Wache Dschinns antworten ab ihrer nächsten Nachricht mit dem neuen Modell. Ein neuer Ort, eine neue Adresse oder ein neuer Schlüssel erreicht sie erst beim nächsten Aufwachen – ebenso alles für einen Dschinn, der weiterlief, während Genies geschlossen war.";
                case SPANISH -> "Los genios despiertos responden con el modelo nuevo a partir de su próximo mensaje. Un sitio, una dirección o una clave nuevos les llegan la próxima vez que despierten, y lo mismo pasa con todo para un genio que siguió en marcha mientras Genies estaba cerrado.";
                case JAPANESE -> "起きているジーニーは、次のメッセージから新しいモデルで答えます。場所・アドレス・キーの変更は次に起きたときに反映されます。Genies を閉じている間も動いていたジーニーには、すべての変更が次に起きたときに反映されます。";
                case CHINESE -> "醒着的精灵会从下一条消息起使用新模型回答。新的位置、地址或密钥要等它们下次醒来时才生效；对于在 Genies 关闭期间仍在运行的精灵，所有更改也都要等下次醒来才生效。";
            };
        }

        public String A_MODELS_NAME_HAS_NO_SPACES_AND_ONLY_PLAIN_LETTERS_DIGITS_AND_SIGNS() {
            return switch (lang) {
                case ENGLISH -> "A model's name has no spaces and only plain letters, digits and signs, such as azure/gpt-6.1-sol.";
                case GERMAN -> "Der Name eines Modells hat keine Leerzeichen, nur einfache Buchstaben, Ziffern und Zeichen, etwa azure/gpt-6.1-sol.";
                case SPANISH -> "El nombre de un modelo no lleva espacios, solo letras sencillas, cifras y signos, como azure/gpt-6.1-sol.";
                case JAPANESE -> "モデル名には空白を含めず、半角の英字・数字・記号だけを使います。例：azure/gpt-6.1-sol";
                case CHINESE -> "模型名称不能包含空格，只能使用半角字母、数字和符号，例如 azure/gpt-6.1-sol。";
            };
        }

        public String NAME_THE_MODEL_THE_GENIES_SHOULD_USE_LOOK_UP_SHOWS_EDEN_AIS_LIST() {
            return switch (lang) {
                case ENGLISH -> "Name the model the genies should use; Look up shows Eden AI's list.";
                case GERMAN -> "Gib das Modell an, das die Dschinns nutzen sollen; „Nachsehen“ zeigt die Liste von Eden AI.";
                case SPANISH -> "Indica el modelo que deben usar los genios; «Consultar» muestra la lista de Eden AI.";
                case JAPANESE -> "ジーニーが使うモデルを指定してください。「一覧を取得」で Eden AI のモデル一覧を表示します。";
                case CHINESE -> "请填写精灵要使用的模型；点击“查询”可查看 Eden AI 的模型列表。";
            };
        }

        public String ENTER_A_KEY_OR_USE_THE_ONE_IN(String variable) {
            return switch (lang) {
                case ENGLISH -> "Enter a key, or use the one in " + variable + ".";
                case GERMAN -> "Gib einen Schlüssel ein, oder nimm den aus " + variable + ".";
                case SPANISH -> "Introduce una clave, o usa la de " + variable + ".";
                case JAPANESE -> "キーを入力するか、" + variable + " のキーを使ってください。";
                case CHINESE -> "请输入密钥，或使用 " + variable + " 中的密钥。";
            };
        }

        public String IS_NOT_SET_WHERE_GENIES_WAS_STARTED_ENTER_A_KEY_IN_THE_SETTINGS(String variable) {
            return switch (lang) {
                case ENGLISH -> variable + " is not set where Genies was started. Enter a key in the settings, or set it and start Genies again.";
                case GERMAN -> variable + " ist dort nicht gesetzt, wo Genies gestartet wurde. Gib in den Einstellungen einen Schlüssel ein, oder setze die Variable und starte Genies neu.";
                case SPANISH -> variable + " no está definida donde se inició Genies. Introduce una clave en los ajustes, o defínela y vuelve a iniciar Genies.";
                case JAPANESE -> variable + " は Genies を起動した環境で設定されていません。設定でキーを入力するか、変数を設定して Genies を起動し直してください。";
                case CHINESE -> "启动 Genies 的环境中未设置 " + variable + "。请在设置中输入密钥，或设置该变量后重新启动 Genies。";
            };
        }

        public String ENTER_THE_SERVERS_ADDRESS() {
            return switch (lang) {
                case ENGLISH -> "Enter the server's address, such as https://ollama.example.com/v1.";
                case GERMAN -> "Gib die Adresse des Servers ein, etwa https://ollama.example.com/v1.";
                case SPANISH -> "Introduce la dirección del servidor, como https://ollama.example.com/v1.";
                case JAPANESE -> "サーバーのアドレスを入力してください。例：https://ollama.example.com/v1";
                case CHINESE -> "请输入服务器地址，例如 https://ollama.example.com/v1。";
            };
        }

        public String NAME_THE_MODEL_AS_THE_SERVER_LISTS_IT() {
            return switch (lang) {
                case ENGLISH -> "Name the model, as the server lists it; Look up shows its list.";
                case GERMAN -> "Gib das Modell so an, wie der Server es auflistet; „Nachsehen“ zeigt seine Liste.";
                case SPANISH -> "Indica el modelo tal como lo muestra el servidor; «Consultar» muestra su lista.";
                case JAPANESE -> "サーバーの一覧にあるとおりにモデル名を指定してください。「一覧を取得」で一覧を表示します。";
                case CHINESE -> "请按服务器列出的名称填写模型；点击“查询”可查看其列表。";
            };
        }

        public String A_MODEL_SERVER_ON_THIS_COMPUTER_IS_REACHED_AT_127_0_0_1_OR_LOCALHOST() {
            return switch (lang) {
                case ENGLISH -> "A model server on this computer is reached at 127.0.0.1 or localhost, such as http://127.0.0.1:11434/v1.";
                case GERMAN -> "Einen Modellserver auf diesem Rechner erreichst du unter 127.0.0.1 oder localhost, etwa http://127.0.0.1:11434/v1.";
                case SPANISH -> "A un servidor de modelos de este equipo se llega por 127.0.0.1 o localhost, como http://127.0.0.1:11434/v1.";
                case JAPANESE -> "このコンピューター上のモデルサーバーには 127.0.0.1 か localhost で接続します。例：http://127.0.0.1:11434/v1";
                case CHINESE -> "这台电脑上的模型服务器可通过 127.0.0.1 或 localhost 访问，例如 http://127.0.0.1:11434/v1。";
            };
        }

        public String NAME_THE_MODEL_AS_THE_MODEL_SERVER_LISTS_IT() {
            return switch (lang) {
                case ENGLISH -> "Name the model, as the model server lists it; Look up shows its list.";
                case GERMAN -> "Gib das Modell so an, wie der Modellserver es auflistet; „Nachsehen“ zeigt seine Liste.";
                case SPANISH -> "Indica el modelo tal como lo muestra el servidor de modelos; «Consultar» muestra su lista.";
                case JAPANESE -> "モデルサーバーの一覧にあるとおりにモデル名を指定してください。「一覧を取得」で一覧を表示します。";
                case CHINESE -> "请按模型服务器列出的名称填写模型；点击“查询”可查看其列表。";
            };
        }

        public String THE_ADDRESS_SHOULD_LOOK_LIKE_AN_ADDRESS_OF_A_MODEL_SERVER() {
            return switch (lang) {
                case ENGLISH -> "The address should look like https://ollama.example.com/v1 or http://127.0.0.1:11434/v1.";
                case GERMAN -> "Die Adresse sollte aussehen wie https://ollama.example.com/v1 oder http://127.0.0.1:11434/v1.";
                case SPANISH -> "La dirección debería parecerse a https://ollama.example.com/v1 o http://127.0.0.1:11434/v1.";
                case JAPANESE -> "アドレスは https://ollama.example.com/v1 や http://127.0.0.1:11434/v1 のような形式にしてください。";
                case CHINESE -> "地址应类似于 https://ollama.example.com/v1 或 http://127.0.0.1:11434/v1。";
            };
        }

        public String THE_ADDRESS_SHOULD_BE_A_SCHEME_A_HOST_AND_PERHAPS_A_PORT_AND_A_PATH() {
            return switch (lang) {
                case ENGLISH -> "The address should be a scheme, a host, and perhaps a port and a path, such as https://ollama.example.com/v1 or http://127.0.0.1:11434/v1.";
                case GERMAN -> "Die Adresse besteht aus einem Schema, einem Host und gegebenenfalls einem Port und einem Pfad, etwa https://ollama.example.com/v1 oder http://127.0.0.1:11434/v1.";
                case SPANISH -> "La dirección debe tener un esquema, un host y, si acaso, un puerto y una ruta, como https://ollama.example.com/v1 o http://127.0.0.1:11434/v1.";
                case JAPANESE -> "アドレスは、スキームとホスト、必要ならポートとパスで構成します。例：https://ollama.example.com/v1、http://127.0.0.1:11434/v1";
                case CHINESE -> "地址应由协议、主机名，以及可选的端口和路径组成，例如 https://ollama.example.com/v1 或 http://127.0.0.1:11434/v1。";
            };
        }

        public String THE_KEY_IS_SENT_TO_THE_SERVER_SO_IT_MUST_USE_HTTPS() {
            return switch (lang) {
                case ENGLISH -> "The key is sent to the server, so it must use https:// (http:// only on this computer).";
                case GERMAN -> "Der Schlüssel wird an den Server geschickt, darum muss er https:// verwenden (http:// nur auf diesem Rechner).";
                case SPANISH -> "La clave se envía al servidor, así que debe usar https:// (http:// solo en este equipo).";
                case JAPANESE -> "キーはサーバーに送られるため、https:// を使う必要があります（http:// はこのコンピューター上の場合のみ）。";
                case CHINESE -> "密钥会发送给服务器，因此必须使用 https://（仅在本机时可用 http://）。";
            };
        }

        public String INSTALL_OLLAMA_AND_GET(String wanted) {
            return switch (lang) {
                case ENGLISH -> "Install Ollama and get " + wanted;
                case GERMAN -> "Ollama installieren und " + wanted + " holen";
                case SPANISH -> "Instalar Ollama y descargar " + wanted;
                case JAPANESE -> "Ollama をインストールして " + wanted + " を入手";
                case CHINESE -> "安装 Ollama 并获取 " + wanted;
            };
        }

        public String GET(String wanted) {
            return switch (lang) {
                case ENGLISH -> "Get " + wanted;
                case GERMAN -> wanted + " holen";
                case SPANISH -> "Descargar " + wanted;
                case JAPANESE -> wanted + " を入手";
                case CHINESE -> "获取 " + wanted;
            };
        }

        public String USE_MODEL(String wanted) {
            return switch (lang) {
                case ENGLISH -> "Use " + wanted;
                case GERMAN -> wanted + " verwenden";
                case SPANISH -> "Usar " + wanted;
                case JAPANESE -> wanted + " を使う";
                case CHINESE -> "使用 " + wanted;
            };
        }

        public String GENIES_IS_LOOKING_WHETHER_OLLAMA_IS_ON_THIS_COMPUTER() {
            return switch (lang) {
                case ENGLISH -> "Genies is looking whether Ollama is on this computer…";
                case GERMAN -> "Genies schaut, ob Ollama auf diesem Rechner ist…";
                case SPANISH -> "Genies está comprobando si Ollama está en este equipo…";
                case JAPANESE -> "Genies が、このコンピューターに Ollama があるか確認しています…";
                case CHINESE -> "Genies 正在检查这台电脑上是否装有 Ollama…";
            };
        }

        public String OLLAMA_RUNS_ON_THIS_COMPUTER(String version) {
            return switch (lang) {
                case ENGLISH -> "Ollama " + version + " runs on this computer.";
                case GERMAN -> "Ollama " + version + " läuft auf diesem Rechner.";
                case SPANISH -> "Ollama " + version + " funciona en este equipo.";
                case JAPANESE -> "このコンピューターで Ollama " + version + " が動いています。";
                case CHINESE -> "这台电脑上正在运行 Ollama " + version + "。";
            };
        }

        public String OLLAMA_IS_INSTALLED_GENIES_STARTS_IT_WHEN_THE_GENIES_NEED_IT() {
            return switch (lang) {
                case ENGLISH -> "Ollama is installed; Genies starts it when the genies need it.";
                case GERMAN -> "Ollama ist installiert; Genies startet es, wenn die Dschinns es brauchen.";
                case SPANISH -> "Ollama está instalado; Genies lo inicia cuando los genios lo necesitan.";
                case JAPANESE -> "Ollama はインストール済みです。ジーニーが必要とするときに Genies が起動します。";
                case CHINESE -> "Ollama 已安装；精灵需要时 Genies 会启动它。";
            };
        }

        public String OLLAMA_IS_NOT_INSTALLED_YET_GENIES_INSTALLS_IT_FOR_YOU() {
            return switch (lang) {
                case ENGLISH ->
                    "Ollama is not installed yet. Genies installs it for you, for you alone: no password, "
                    + "nothing changes outside its own folder. It is a download of about 1.4 GB.";
                case GERMAN -> "Ollama ist noch nicht installiert. Genies installiert es für dich, nur für dich: ohne Passwort, und außerhalb seines eigenen Ordners ändert sich nichts. Der Download ist etwa 1,4 GB groß.";
                case SPANISH -> "Ollama aún no está instalado. Genies lo instala por ti, solo para ti: sin contraseña, y sin cambiar nada fuera de su propia carpeta. Son unos 1,4 GB de descarga.";
                case JAPANESE -> "Ollama はまだインストールされていません。Genies があなた専用にインストールします。パスワードは不要で、専用フォルダーの外は何も変わりません。ダウンロードは約 1.4 GB です。";
                case CHINESE -> "尚未安装 Ollama。Genies 会为你安装，仅供你自己使用：无需密码，也不会改动它自己文件夹以外的任何内容。需要下载约 1.4 GB。";
            };
        }

        public String GENIES_COULD_NOT_READ_HOW_MUCH_MEMORY_THIS_COMPUTER_HAS() {
            return switch (lang) {
                case ENGLISH -> "Genies could not read how much memory this computer has.";
                case GERMAN -> "Genies konnte nicht herausfinden, wie viel Arbeitsspeicher dieser Rechner hat.";
                case SPANISH -> "Genies no pudo averiguar cuánta memoria tiene este equipo.";
                case JAPANESE -> "Genies は、このコンピューターのメモリ容量を読み取れませんでした。";
                case CHINESE -> "Genies 无法读取这台电脑的内存大小。";
            };
        }

        public String MEMORY_AND_NO_GRAPHICS_CARD_A_MODEL_CAN_RUN_ON(String memory) {
            return switch (lang) {
                case ENGLISH -> memory + " of memory, and no graphics card a model can run on.";
                case GERMAN -> memory + " Arbeitsspeicher und keine Grafikkarte, auf der ein Modell laufen kann.";
                case SPANISH -> memory + " de memoria, y ninguna tarjeta gráfica en la que pueda funcionar un modelo.";
                case JAPANESE -> "メモリ " + memory + "。モデルを動かせるグラフィックカードはありません。";
                case CHINESE -> "内存 " + memory + "，没有可运行模型的显卡。";
            };
        }

        public String MEMORY_AND_THE_GRAPHICS_CARD(String memory, String card, String cardMemory) {
            return switch (lang) {
                case ENGLISH -> memory + " of memory, and the graphics card " + card + " with " + cardMemory + ".";
                case GERMAN -> memory + " Arbeitsspeicher und die Grafikkarte " + card + " mit " + cardMemory + ".";
                case SPANISH -> memory + " de memoria, y la tarjeta gráfica " + card + " con " + cardMemory + ".";
                case JAPANESE -> "メモリ " + memory + "、グラフィックカード " + card + "（" + cardMemory + "）。";
                case CHINESE -> "内存 " + memory + "，显卡 " + card + "（显存 " + cardMemory + "）。";
            };
        }

        public String ABOUT_SO_MUCH_TO_DOWNLOAD(String size) {
            return switch (lang) {
                case ENGLISH -> "About " + size + " to download.";
                case GERMAN -> "Etwa " + size + " zum Herunterladen.";
                case SPANISH -> "Unos " + size + " de descarga.";
                case JAPANESE -> "ダウンロードは約 " + size + " です。";
                case CHINESE -> "需要下载约 " + size + "。";
            };
        }

        public String ITS_NAME_DOES_NOT_SAY_HOW_LARGE_IT_IS() {
            return switch (lang) {
                case ENGLISH -> "Its name does not say how large it is, so Genies cannot tell how well it runs here.";
                case GERMAN -> "Sein Name verrät nicht, wie groß es ist, deshalb kann Genies nicht sagen, wie gut es hier läuft.";
                case SPANISH -> "Su nombre no dice qué tamaño tiene, así que Genies no puede saber lo bien que funcionará aquí.";
                case JAPANESE -> "名前からサイズがわからないため、ここでどの程度快適に動くか Genies には判断できません。";
                case CHINESE -> "从名称看不出它的大小，所以 Genies 无法判断它在这里的运行效果。";
            };
        }

        public String IT_RUNS_ON_THE_GRAPHICS_CARD_SO_IT_ANSWERS_QUICKLY() {
            return switch (lang) {
                case ENGLISH -> "It runs on the graphics card, so it answers quickly.";
                case GERMAN -> "Es läuft auf der Grafikkarte und antwortet daher schnell.";
                case SPANISH -> "Funciona en la tarjeta gráfica, así que responde rápido.";
                case JAPANESE -> "グラフィックカードで動くので、すばやく答えます。";
                case CHINESE -> "它在显卡上运行，所以回答很快。";
            };
        }

        public String IT_RUNS_ON_THE_PROCESSOR_AT_A_FAIR_PACE() {
            return switch (lang) {
                case ENGLISH -> "It runs on the processor, at a fair pace.";
                case GERMAN -> "Es läuft auf dem Prozessor, in ordentlichem Tempo.";
                case SPANISH -> "Funciona en el procesador, a buen ritmo.";
                case JAPANESE -> "プロセッサーで動き、そこそこの速さで答えます。";
                case CHINESE -> "它在处理器上运行，速度尚可。";
            };
        }

        public String IT_RUNS_ON_THE_PROCESSOR_SO_IT_ANSWERS_SLOWLY() {
            return switch (lang) {
                case ENGLISH -> "It runs on the processor, so it answers slowly.";
                case GERMAN -> "Es läuft auf dem Prozessor und antwortet daher langsam.";
                case SPANISH -> "Funciona en el procesador, así que responde despacio.";
                case JAPANESE -> "プロセッサーで動くため、答えるのに時間がかかります。";
                case CHINESE -> "它在处理器上运行，所以回答较慢。";
            };
        }

        public String RUNNING_IT_NEEDS_MORE_MEMORY_THAN_THIS_COMPUTER_CAN_SPARE(String need) {
            return switch (lang) {
                case ENGLISH -> "Running, it needs about " + need + " of memory, more than this computer can spare: it may not load, or be very slow.";
                case GERMAN -> "Im Betrieb braucht es etwa " + need + " Arbeitsspeicher, mehr als dieser Rechner erübrigen kann: Es lädt vielleicht nicht, oder ist sehr langsam.";
                case SPANISH -> "Para funcionar necesita unos " + need + " de memoria, más de lo que este equipo puede dedicarle: puede que no cargue, o que vaya muy lento.";
                case JAPANESE -> "動かすには約 " + need + " のメモリが必要で、このコンピューターで使える量を超えています。読み込めないか、非常に遅くなるかもしれません。";
                case CHINESE -> "运行时大约需要 " + need + " 内存，超出了这台电脑能腾出的容量：可能无法加载，或者会非常慢。";
            };
        }
    }

    /// The words of a genie's Schedule page: when jobs wake the genie, and what they did.
    public record SchedulePage(Lang lang) {
        public String PAUSED_NO_JOB_RUNS_UNTIL_YOU_RESUME_THE_SCHEDULE() {
            return switch (lang) {
                case ENGLISH -> "Paused. No job runs until you resume the schedule.";
                case GERMAN -> "Pausiert. Kein Auftrag läuft, bis du den Zeitplan fortsetzt.";
                case SPANISH -> "En pausa. No se ejecuta ninguna tarea hasta que reanudes la programación.";
                case JAPANESE -> "一時停止中です。スケジュールを再開するまで、ジョブは実行されません。";
                case CHINESE -> "已暂停。在你恢复计划之前，不会运行任何任务。";
            };
        }

        public String ON_JOBS_WAKE_IT_AT_THEIR_TIMES(String genie) {
            return switch (lang) {
                case ENGLISH -> "On. Jobs wake " + genie + " at their times, one after the other.";
                case GERMAN -> "An. Aufträge wecken " + genie + " zu ihren Zeiten, einer nach dem anderen.";
                case SPANISH -> "Activada. Las tareas despiertan a " + genie + " a su hora, una tras otra.";
                case JAPANESE -> "オン。ジョブはそれぞれの時刻に " + genie + " を起こし、一つずつ実行されます。";
                case CHINESE -> "已开启。任务会按各自的时间依次唤醒 " + genie + "。";
            };
        }

        public String IS_WAKING_JOBS_RUN_ONCE_IT_IS_AWAKE(String genie) {
            return switch (lang) {
                case ENGLISH -> genie + " is waking. Jobs run once it is awake.";
                case GERMAN -> genie + " wacht auf. Aufträge laufen, sobald er wach ist.";
                case SPANISH -> genie + " se está despertando. Las tareas se ejecutan en cuanto esté despierto.";
                case JAPANESE -> genie + " が起きようとしています。起きたらジョブが実行されます。";
                case CHINESE -> genie + " 正在醒来。醒来后就会运行任务。";
            };
        }

        public String IS_ASLEEP_JOBS_RUN_ONLY_WHILE_IT_IS_AWAKE(String genie) {
            return switch (lang) {
                case ENGLISH -> genie + " is asleep. Jobs run only while it is awake; one whose time came meanwhile runs as soon as it wakes.";
                case GERMAN -> genie + " schläft. Aufträge laufen nur, solange er wach ist; einer, dessen Zeit inzwischen gekommen ist, läuft, sobald er aufwacht.";
                case SPANISH -> genie + " está dormido. Las tareas solo se ejecutan mientras está despierto; si a una le llega la hora entretanto, se ejecuta en cuanto despierte.";
                case JAPANESE -> genie + " は眠っています。ジョブは起きている間だけ実行されます。眠っている間に時刻になったジョブは、起きたらすぐに実行されます。";
                case CHINESE -> genie + " 正在休眠。任务只在它醒着时运行；休眠期间到点的任务会在它醒来后立即运行。";
            };
        }

        public String SCHEDULE() {
            return switch (lang) {
                case ENGLISH -> "Schedule";
                case GERMAN -> "Zeitplan";
                case SPANISH -> "Programación";
                case JAPANESE -> "スケジュール";
                case CHINESE -> "计划";
            };
        }

        public String WAKE() {
            return switch (lang) {
                case ENGLISH -> "Wake";
                case GERMAN -> "Wecken";
                case SPANISH -> "Despertar";
                case JAPANESE -> "起こす";
                case CHINESE -> "唤醒";
            };
        }

        public String JOBS_RUN_ONLY_WHILE_THE_GENIE_IS_AWAKE() {
            return switch (lang) {
                case ENGLISH -> "Jobs run only while the genie is awake";
                case GERMAN -> "Aufträge laufen nur, solange der Dschinn wach ist";
                case SPANISH -> "Las tareas solo se ejecutan mientras el genio está despierto";
                case JAPANESE -> "ジョブはジーニーが起きている間だけ実行されます";
                case CHINESE -> "任务只在精灵醒着时运行";
            };
        }

        public String RESUME() {
            return switch (lang) {
                case ENGLISH -> "Resume";
                case GERMAN -> "Fortsetzen";
                case SPANISH -> "Reanudar";
                case JAPANESE -> "再開";
                case CHINESE -> "恢复";
            };
        }

        public String PAUSE() {
            return switch (lang) {
                case ENGLISH -> "Pause";
                case GERMAN -> "Pausieren";
                case SPANISH -> "Pausar";
                case JAPANESE -> "一時停止";
                case CHINESE -> "暂停";
            };
        }

        public String LET_THE_JOBS_RUN_AGAIN() {
            return switch (lang) {
                case ENGLISH -> "Let the jobs run again";
                case GERMAN -> "Die Aufträge wieder laufen lassen";
                case SPANISH -> "Dejar que las tareas vuelvan a ejecutarse";
                case JAPANESE -> "ジョブの実行を再開します";
                case CHINESE -> "让任务重新开始运行";
            };
        }

        public String HOLD_EVERY_JOB_UNTIL_YOU_RESUME_NOTHING_IS_LOST() {
            return switch (lang) {
                case ENGLISH -> "Hold every job until you resume; nothing is lost";
                case GERMAN -> "Alle Aufträge zurückhalten, bis du fortsetzt; nichts geht verloren";
                case SPANISH -> "Retener todas las tareas hasta que reanudes; no se pierde nada";
                case JAPANESE -> "再開するまで、すべてのジョブを保留します。何も失われません";
                case CHINESE -> "暂停所有任务，直到你恢复；不会丢失任何内容";
            };
        }

        public String NEW_JOB() {
            return switch (lang) {
                case ENGLISH -> "New job";
                case GERMAN -> "Neuer Auftrag";
                case SPANISH -> "Nueva tarea";
                case JAPANESE -> "新しいジョブ";
                case CHINESE -> "新建任务";
            };
        }

        public String THE_WEEK() {
            return switch (lang) {
                case ENGLISH -> "THE WEEK";
                case GERMAN -> "DIE WOCHE";
                case SPANISH -> "LA SEMANA";
                case JAPANESE -> "この 1 週間";
                case CHINESE -> "本周";
            };
        }

        public String SHOW_ONLY_THE_LAST_RUNS() {
            return switch (lang) {
                case ENGLISH -> "Show only the last runs";
                case GERMAN -> "Nur die letzten Läufe zeigen";
                case SPANISH -> "Mostrar solo las últimas ejecuciones";
                case JAPANESE -> "最近の実行だけを表示";
                case CHINESE -> "只显示最近的运行";
            };
        }

        public String SHOW_EARLIER_RUNS(int count) {
            return switch (lang) {
                case ENGLISH -> "Show " + count + " earlier " + (count == 1 ? "run" : "runs");
                case GERMAN -> count + (count == 1 ? " früheren Lauf zeigen" : " frühere Läufe zeigen");
                case SPANISH -> "Mostrar " + count + (count == 1 ? " ejecución anterior" : " ejecuciones anteriores");
                case JAPANESE -> "以前の実行を " + count + " 件表示";
                case CHINESE -> "显示更早的 " + count + " 次运行";
            };
        }

        public String READING_THE_SCHEDULE() {
            return switch (lang) {
                case ENGLISH -> "Reading the schedule…";
                case GERMAN -> "Lese den Zeitplan…";
                case SPANISH -> "Leyendo la programación…";
                case JAPANESE -> "スケジュールを読み込み中…";
                case CHINESE -> "正在读取计划…";
            };
        }

        public String NOTHING_RAN_THIS_WEEK_AND_NOTHING_IS_PLANNED() {
            return switch (lang) {
                case ENGLISH -> "Nothing ran this week, and nothing is planned.";
                case GERMAN -> "Diese Woche lief nichts, und nichts ist geplant.";
                case SPANISH -> "Esta semana no se ha ejecutado nada, y no hay nada previsto.";
                case JAPANESE -> "今週は何も実行されておらず、予定もありません。";
                case CHINESE -> "本周没有运行过任何任务，也没有任何安排。";
            };
        }

        public String CLICK_AGAIN_TO_SEE_EVERY_JOB() {
            return switch (lang) {
                case ENGLISH -> "Click again to see every job";
                case GERMAN -> "Nochmals klicken, um alle Aufträge zu sehen";
                case SPANISH -> "Haz clic otra vez para ver todas las tareas";
                case JAPANESE -> "もう一度クリックすると、すべてのジョブを表示します";
                case CHINESE -> "再次点击查看所有任务";
            };
        }

        public String CLICK_TO_BRING_THIS_JOBS_TIMES_FORWARD() {
            return switch (lang) {
                case ENGLISH -> "Click to bring this job's times forward";
                case GERMAN -> "Klicken, um die Zeiten dieses Auftrags hervorzuheben";
                case SPANISH -> "Haz clic para destacar las horas de esta tarea";
                case JAPANESE -> "クリックすると、このジョブの時刻を強調表示します";
                case CHINESE -> "点击突出显示此任务的时间";
            };
        }

        public String ADDED_BY(String genie) {
            return switch (lang) {
                case ENGLISH -> "added by " + genie;
                case GERMAN -> "hinzugefügt von " + genie;
                case SPANISH -> "añadida por " + genie;
                case JAPANESE -> genie + " が追加";
                case CHINESE -> "由 " + genie + " 添加";
            };
        }

        public String OPEN_THE_CONVERSATION() {
            return switch (lang) {
                case ENGLISH -> "Open the conversation";
                case GERMAN -> "Gespräch öffnen";
                case SPANISH -> "Abrir la conversación";
                case JAPANESE -> "会話を開く";
                case CHINESE -> "打开对话";
            };
        }

        public String STOP() {
            return switch (lang) {
                case ENGLISH -> "Stop";
                case GERMAN -> "Anhalten";
                case SPANISH -> "Detener";
                case JAPANESE -> "停止";
                case CHINESE -> "停止";
            };
        }

        public String STOP_THIS_RUN_WHAT_THE_GENIE_DID_SO_FAR_IS_KEPT() {
            return switch (lang) {
                case ENGLISH -> "Stop this run; what the genie did so far is kept, and saved";
                case GERMAN -> "Diesen Lauf anhalten; was der Dschinn bis dahin getan hat, bleibt erhalten und wird gesichert";
                case SPANISH -> "Detener esta ejecución; lo que el genio ha hecho hasta ahora se conserva y se guarda";
                case JAPANESE -> "この実行を止めます。ジーニーがそこまでに行ったことは残り、保存されます";
                case CHINESE -> "停止本次运行；精灵到目前为止所做的都会保留并保存";
            };
        }

        public String JOBS(int count) {
            return switch (lang) {
                case ENGLISH -> count == 0 ? "JOBS" : "JOBS  ·  " + count;
                case GERMAN -> count == 0 ? "AUFTRÄGE" : "AUFTRÄGE  ·  " + count;
                case SPANISH -> count == 0 ? "TAREAS" : "TAREAS  ·  " + count;
                case JAPANESE -> count == 0 ? "ジョブ" : "ジョブ  ·  " + count;
                case CHINESE -> count == 0 ? "任务" : "任务  ·  " + count;
            };
        }

        public String NOTHING_ON_THE_SCHEDULE_YET() {
            return switch (lang) {
                case ENGLISH -> "Nothing on the schedule yet";
                case GERMAN -> "Noch nichts im Zeitplan";
                case SPANISH -> "Aún no hay nada programado";
                case JAPANESE -> "スケジュールはまだ空です";
                case CHINESE -> "计划中还没有任务";
            };
        }

        public String GIVE_IT_A_TASK_AND_A_TIME(String genie) {
            return switch (lang) {
                case ENGLISH ->
                    "Give " + genie + " a task and a time, "
                    + "once or again and again: a morning check of the build, a weekly report, a reminder "
                    + "to tidy up. It works on it in a conversation of its own, and you can read it here afterwards.";
                case GERMAN -> "Gib " + genie + " eine Aufgabe und eine Zeit, einmalig oder immer wieder: ein morgendlicher Blick auf den Build, ein Wochenbericht, eine Erinnerung zum Aufräumen. Er erledigt sie in einem eigenen Gespräch, das du hinterher hier nachlesen kannst.";
                case SPANISH -> "Dale a " + genie + " una tarea y una hora, una sola vez o de forma periódica: revisar la compilación cada mañana, un informe semanal, un recordatorio para ordenar. La hace en una conversación propia, que luego puedes leer aquí.";
                case JAPANESE -> genie + " にタスクと時刻を与えましょう。一度きりでも、繰り返しでも構いません。毎朝のビルド確認、週次レポート、片付けのリマインダーなど。ジーニーは専用の会話で作業し、その内容はあとでここで読めます。";
                case CHINESE -> "给 " + genie + " 一项任务和一个时间，可以只做一次，也可以反复执行：每天早上检查构建、每周写一份报告、提醒你整理。它会在单独的对话中完成，之后你可以在这里查看。";
            };
        }

        public String CHANGE_SWITCH_OFF_OR_REMOVE_THIS_JOB() {
            return switch (lang) {
                case ENGLISH -> "Change, switch off or remove this job";
                case GERMAN -> "Diesen Auftrag ändern, ausschalten oder entfernen";
                case SPANISH -> "Cambiar, desactivar o quitar esta tarea";
                case JAPANESE -> "このジョブを変更、オフ、または削除します";
                case CHINESE -> "修改、关闭或移除此任务";
            };
        }

        public String UNTIL(String day) {
            return switch (lang) {
                case ENGLISH -> "until " + day;
                case GERMAN -> "bis " + day;
                case SPANISH -> "hasta " + day;
                case JAPANESE -> day + "まで";
                case CHINESE -> "截至" + day;
            };
        }

        public String SWITCHED_OFF() {
            return switch (lang) {
                case ENGLISH -> "switched off";
                case GERMAN -> "ausgeschaltet";
                case SPANISH -> "desactivada";
                case JAPANESE -> "オフ";
                case CHINESE -> "已关闭";
            };
        }

        public String WILL_NOT_RUN_AGAIN() {
            return switch (lang) {
                case ENGLISH -> "will not run again";
                case GERMAN -> "läuft nicht mehr";
                case SPANISH -> "no volverá a ejecutarse";
                case JAPANESE -> "今後は実行されません";
                case CHINESE -> "不会再运行";
            };
        }

        public String DUE_NOW() {
            return switch (lang) {
                case ENGLISH -> "due now";
                case GERMAN -> "jetzt fällig";
                case SPANISH -> "le toca ahora";
                case JAPANESE -> "実行待ち";
                case CHINESE -> "已到时间";
            };
        }

        public String TODAY() {
            return switch (lang) {
                case ENGLISH -> "today";
                case GERMAN -> "heute";
                case SPANISH -> "hoy";
                case JAPANESE -> "今日";
                case CHINESE -> "今天";
            };
        }

        public String TOMORROW() {
            return switch (lang) {
                case ENGLISH -> "tomorrow";
                case GERMAN -> "morgen";
                case SPANISH -> "mañana";
                case JAPANESE -> "明日";
                case CHINESE -> "明天";
            };
        }

        public String NEXT(String day, String time) {
            return switch (lang) {
                case ENGLISH -> "next: " + day + ", " + time;
                case GERMAN -> "nächster Lauf: " + day + ", " + time;
                case SPANISH -> "próxima: " + day + ", " + time;
                case JAPANESE -> "次回：" + day + " " + time;
                case CHINESE -> "下次：" + day + " " + time;
            };
        }

        public String CHANGE_ELLIPSIS() {
            return switch (lang) {
                case ENGLISH -> "Change…";
                case GERMAN -> "Ändern…";
                case SPANISH -> "Cambiar…";
                case JAPANESE -> "変更…";
                case CHINESE -> "修改…";
            };
        }

        public String SWITCH_OFF() {
            return switch (lang) {
                case ENGLISH -> "Switch off";
                case GERMAN -> "Ausschalten";
                case SPANISH -> "Desactivar";
                case JAPANESE -> "オフにする";
                case CHINESE -> "关闭";
            };
        }

        public String SWITCH_ON() {
            return switch (lang) {
                case ENGLISH -> "Switch on";
                case GERMAN -> "Einschalten";
                case SPANISH -> "Activar";
                case JAPANESE -> "オンにする";
                case CHINESE -> "开启";
            };
        }

        public String REMOVE_ELLIPSIS() {
            return switch (lang) {
                case ENGLISH -> "Remove…";
                case GERMAN -> "Entfernen…";
                case SPANISH -> "Quitar…";
                case JAPANESE -> "削除…";
                case CHINESE -> "移除…";
            };
        }

        public String TAKE_IT_OFF_THE_SCHEDULE_WHAT_ITS_RUNS_DID_STAYS_IN_THE_HISTORY(String job) {
            return switch (lang) {
                case ENGLISH -> "Take \"" + job + "\" off the schedule? What its runs did stays in the history.";
                case GERMAN -> "„" + job + "“ aus dem Zeitplan nehmen? Was seine Läufe getan haben, bleibt im Verlauf.";
                case SPANISH -> "¿Quitar «" + job + "» de la programación? Lo que hicieron sus ejecuciones se queda en el historial.";
                case JAPANESE -> "「" + job + "」をスケジュールから削除しますか？これまでの実行内容は履歴に残ります。";
                case CHINESE -> "要将“" + job + "”从计划中移除吗？它运行过的内容会保留在历史中。";
            };
        }

        public String REMOVE_A_JOB() {
            return switch (lang) {
                case ENGLISH -> "Remove a job";
                case GERMAN -> "Auftrag entfernen";
                case SPANISH -> "Quitar una tarea";
                case JAPANESE -> "ジョブの削除";
                case CHINESE -> "移除任务";
            };
        }

        public String REMOVE() {
            return switch (lang) {
                case ENGLISH -> "Remove";
                case GERMAN -> "Entfernen";
                case SPANISH -> "Quitar";
                case JAPANESE -> "削除";
                case CHINESE -> "移除";
            };
        }

        public String KEEP() {
            return switch (lang) {
                case ENGLISH -> "Keep";
                case GERMAN -> "Behalten";
                case SPANISH -> "Conservar";
                case JAPANESE -> "残す";
                case CHINESE -> "保留";
            };
        }

        public String ON_CLICK_TO_SWITCH_IT_OFF_KEEPING_IT_ON_THE_SCHEDULE() {
            return switch (lang) {
                case ENGLISH -> "On: click to switch it off, keeping it on the schedule";
                case GERMAN -> "An: klicken, um ihn auszuschalten; er bleibt im Zeitplan";
                case SPANISH -> "Activada: haz clic para desactivarla; seguirá en la programación";
                case JAPANESE -> "オン：クリックでオフにします。スケジュールには残ります";
                case CHINESE -> "已开启：点击即可关闭，任务仍保留在计划中";
            };
        }

        public String OFF_CLICK_TO_SWITCH_IT_ON() {
            return switch (lang) {
                case ENGLISH -> "Off: click to switch it on";
                case GERMAN -> "Aus: klicken, um ihn einzuschalten";
                case SPANISH -> "Desactivada: haz clic para activarla";
                case JAPANESE -> "オフ：クリックでオンにします";
                case CHINESE -> "已关闭：点击即可开启";
            };
        }

        public String CHANGE_A_JOB() {
            return switch (lang) {
                case ENGLISH -> "Change a job";
                case GERMAN -> "Auftrag ändern";
                case SPANISH -> "Cambiar una tarea";
                case JAPANESE -> "ジョブの変更";
                case CHINESE -> "修改任务";
            };
        }

        public String A_NEW_JOB_FOR(String genie) {
            return switch (lang) {
                case ENGLISH -> "A new job for " + genie;
                case GERMAN -> "Ein neuer Auftrag für " + genie;
                case SPANISH -> "Una tarea nueva para " + genie;
                case JAPANESE -> genie + " の新しいジョブ";
                case CHINESE -> "为 " + genie + " 新建任务";
            };
        }

        public String IT_GETS_THE_TASK_AT_THE_TIME_YOU_CHOOSE_IN_A_CONVERSATION_OF_ITS_OWN() {
            return switch (lang) {
                case ENGLISH ->
                    "It gets the task at the time you choose, in a conversation of its own, "
                    + "and while it is awake: a job whose time came while it slept runs as soon as it wakes.";
                case GERMAN -> "Er bekommt die Aufgabe zur gewählten Zeit, in einem eigenen Gespräch, und solange er wach ist: Ein Auftrag, dessen Zeit kam, während er schlief, läuft, sobald er aufwacht.";
                case SPANISH -> "Recibe la tarea a la hora que elijas, en una conversación propia, y mientras está despierto: una tarea cuya hora llegó mientras dormía se ejecuta en cuanto despierta.";
                case JAPANESE -> "選んだ時刻に、専用の会話でタスクを受け取ります。実行は起きている間だけで、眠っている間に時刻になったジョブは、起きたらすぐに実行されます。";
                case CHINESE -> "它会在你选定的时间、在单独的对话中收到任务，但只在醒着时执行：休眠期间到点的任务会在它醒来后立即运行。";
            };
        }

        public String CLOSE_WITHOUT_SAVING() {
            return switch (lang) {
                case ENGLISH -> "Close without saving";
                case GERMAN -> "Schließen, ohne zu speichern";
                case SPANISH -> "Cerrar sin guardar";
                case JAPANESE -> "保存せずに閉じる";
                case CHINESE -> "关闭且不保存";
            };
        }

        public String CANCEL() {
            return switch (lang) {
                case ENGLISH -> "Cancel";
                case GERMAN -> "Abbrechen";
                case SPANISH -> "Cancelar";
                case JAPANESE -> "キャンセル";
                case CHINESE -> "取消";
            };
        }

        public String SAVING() {
            return switch (lang) {
                case ENGLISH -> "Saving…";
                case GERMAN -> "Speichere…";
                case SPANISH -> "Guardando…";
                case JAPANESE -> "保存中…";
                case CHINESE -> "正在保存…";
            };
        }

        public String SAVE_THE_JOB() {
            return switch (lang) {
                case ENGLISH -> "Save the job";
                case GERMAN -> "Auftrag speichern";
                case SPANISH -> "Guardar la tarea";
                case JAPANESE -> "ジョブを保存";
                case CHINESE -> "保存任务";
            };
        }

        public String ADD_TO_THE_SCHEDULE() {
            return switch (lang) {
                case ENGLISH -> "Add to the schedule";
                case GERMAN -> "Zum Zeitplan hinzufügen";
                case SPANISH -> "Añadir a la programación";
                case JAPANESE -> "スケジュールに追加";
                case CHINESE -> "添加到计划";
            };
        }

        public String CTRL_AND_RETURN_SAVE_TOO() {
            return switch (lang) {
                case ENGLISH -> "Ctrl and Return save too";
                case GERMAN -> "Strg+Eingabe speichert auch";
                case SPANISH -> "Ctrl+Intro también guarda";
                case JAPANESE -> "Ctrl ＋ Enter でも保存できます";
                case CHINESE -> "也可按 Ctrl + Enter 保存";
            };
        }

        public String THE_TASK() {
            return switch (lang) {
                case ENGLISH -> "THE TASK";
                case GERMAN -> "DIE AUFGABE";
                case SPANISH -> "LA TAREA";
                case JAPANESE -> "タスク";
                case CHINESE -> "任务内容";
            };
        }

        public String BRIEF_IT_AS_YOU_WOULD_A_COLLEAGUE() {
            return switch (lang) {
                case ENGLISH ->
                    "Brief it as you would a colleague: what to do, where things are, and "
                    + "what done looks like. Each run starts a fresh conversation; between runs, the genie "
                    + "keeps notes in ~/workspace/NOTES.md.";
                case GERMAN -> "Erklär es so, wie du es jemandem im Team erklären würdest: was zu tun ist, wo die Dinge liegen und woran man erkennt, dass es fertig ist. Jeder Lauf beginnt ein neues Gespräch; zwischen den Läufen führt der Dschinn Notizen in ~/workspace/NOTES.md.";
                case SPANISH -> "Explícaselo como a alguien de tu equipo: qué hay que hacer, dónde están las cosas y cómo sabrá que ha terminado. Cada ejecución empieza una conversación nueva; entre una y otra, el genio toma notas en ~/workspace/NOTES.md.";
                case JAPANESE -> "同僚に頼むときのように説明してください。何をするのか、どこに何があるのか、どうなれば完了なのか。実行のたびに新しい会話が始まり、実行と実行の間、ジーニーは ~/workspace/NOTES.md にメモを残します。";
                case CHINESE -> "像向同事交代工作一样说明：要做什么、东西在哪里、做到什么程度算完成。每次运行都会开始新的对话；两次运行之间，精灵会在 ~/workspace/NOTES.md 中记笔记。";
            };
        }

        public String WHEN() {
            return switch (lang) {
                case ENGLISH -> "WHEN";
                case GERMAN -> "WANN";
                case SPANISH -> "CUÁNDO";
                case JAPANESE -> "実行のタイミング";
                case CHINESE -> "时间";
            };
        }

        public String ONCE() {
            return switch (lang) {
                case ENGLISH -> "Once";
                case GERMAN -> "Einmal";
                case SPANISH -> "Una vez";
                case JAPANESE -> "1 回だけ";
                case CHINESE -> "一次";
            };
        }

        public String AGAIN_AND_AGAIN() {
            return switch (lang) {
                case ENGLISH -> "Again and again";
                case GERMAN -> "Immer wieder";
                case SPANISH -> "Periódicamente";
                case JAPANESE -> "繰り返し";
                case CHINESE -> "重复";
            };
        }

        public String IN_AN_HOUR() {
            return switch (lang) {
                case ENGLISH -> "In an hour";
                case GERMAN -> "In einer Stunde";
                case SPANISH -> "Dentro de una hora";
                case JAPANESE -> "1 時間後";
                case CHINESE -> "一小时后";
            };
        }

        public String THIS_EVENING() {
            return switch (lang) {
                case ENGLISH -> "This evening";
                case GERMAN -> "Heute Abend";
                case SPANISH -> "Esta tarde";
                case JAPANESE -> "今晩";
                case CHINESE -> "今晚";
            };
        }

        public String TOMORROW_MORNING() {
            return switch (lang) {
                case ENGLISH -> "Tomorrow morning";
                case GERMAN -> "Morgen früh";
                case SPANISH -> "Mañana por la mañana";
                case JAPANESE -> "明日の朝";
                case CHINESE -> "明天早上";
            };
        }

        public String MONDAY_MORNING() {
            return switch (lang) {
                case ENGLISH -> "Monday morning";
                case GERMAN -> "Montagmorgen";
                case SPANISH -> "El lunes por la mañana";
                case JAPANESE -> "月曜の朝";
                case CHINESE -> "周一早上";
            };
        }

        public String AT() {
            return switch (lang) {
                case ENGLISH -> "At";
                case GERMAN -> "Um";
                case SPANISH -> "A las";
                case JAPANESE -> "時刻";
                case CHINESE -> "时间";
            };
        }

        public String A_NUMBER_FROM_0_TO_59() {
            return switch (lang) {
                case ENGLISH -> "A number from 0 to 59";
                case GERMAN -> "Eine Zahl von 0 bis 59";
                case SPANISH -> "Un número del 0 al 59";
                case JAPANESE -> "0〜59 の数";
                case CHINESE -> "0 到 59 之间的数字";
            };
        }

        public String MINUTES_PAST_EACH_HOUR() {
            return switch (lang) {
                case ENGLISH -> "minutes past each hour";
                case GERMAN -> "Minuten nach jeder vollen Stunde";
                case SPANISH -> "minutos después de cada hora en punto";
                case JAPANESE -> "分（毎時）";
                case CHINESE -> "分（每小时）";
            };
        }

        public String FIVE_FIELDS_AS_CRON_HAS_THEM() {
            return switch (lang) {
                case ENGLISH ->
                    "Five fields, as cron has them: minute, hour, day of the month, month, "
                    + "day of the week. 0 9 1 * * is nine on the first of each month; */30 * * * * every half hour.";
                case GERMAN -> "Fünf Felder, wie bei cron: Minute, Stunde, Tag des Monats, Monat, Wochentag. 0 9 1 * * ist neun Uhr am Ersten jedes Monats; */30 * * * * jede halbe Stunde.";
                case SPANISH -> "Cinco campos, como en cron: minuto, hora, día del mes, mes y día de la semana. 0 9 1 * * son las nueve del día 1 de cada mes; */30 * * * *, cada media hora.";
                case JAPANESE -> "cron と同じ 5 つのフィールド：分、時、日、月、曜日。0 9 1 * * は毎月 1 日の 9 時、*/30 * * * * は 30 分ごとです。";
                case CHINESE -> "与 cron 相同的五个字段：分、时、日、月、星期。0 9 1 * * 表示每月 1 日 9 点；*/30 * * * * 表示每半小时一次。";
            };
        }

        public String SUCH_AS_09_00_OR_9_30() {
            return switch (lang) {
                case ENGLISH -> "Such as 09:00, or 9.30";
                case GERMAN -> "Etwa 09:00 oder 9.30";
                case SPANISH -> "Como 09:00, o 9.30";
                case JAPANESE -> "例：09:00、9.30";
                case CHINESE -> "例如 09:00 或 9.30";
            };
        }

        public String ENDS() {
            return switch (lang) {
                case ENGLISH -> "Ends";
                case GERMAN -> "Endet";
                case SPANISH -> "Termina";
                case JAPANESE -> "終了";
                case CHINESE -> "结束";
            };
        }

        public String NEVER() {
            return switch (lang) {
                case ENGLISH -> "Never";
                case GERMAN -> "Nie";
                case SPANISH -> "Nunca";
                case JAPANESE -> "なし";
                case CHINESE -> "从不";
            };
        }

        public String ON_A_DAY() {
            return switch (lang) {
                case ENGLISH -> "On a day";
                case GERMAN -> "An einem Tag";
                case SPANISH -> "Un día concreto";
                case JAPANESE -> "指定日";
                case CHINESE -> "指定日期";
            };
        }

        public String THE_MONTH_BEFORE() {
            return switch (lang) {
                case ENGLISH -> "The month before";
                case GERMAN -> "Voriger Monat";
                case SPANISH -> "Mes anterior";
                case JAPANESE -> "前の月";
                case CHINESE -> "上个月";
            };
        }

        public String THE_MONTH_AFTER() {
            return switch (lang) {
                case ENGLISH -> "The month after";
                case GERMAN -> "Nächster Monat";
                case SPANISH -> "Mes siguiente";
                case JAPANESE -> "次の月";
                case CHINESE -> "下个月";
            };
        }

        public String MONTH_AND_YEAR(YearMonth month) {
            return switch (lang) {
                case ENGLISH -> month.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + month.getYear();
                case GERMAN -> month.getMonth().getDisplayName(TextStyle.FULL_STANDALONE, Locale.GERMAN) + " " + month.getYear();
                case SPANISH -> month.getMonth().getDisplayName(TextStyle.FULL_STANDALONE, Locale.of("es")) + " de " + month.getYear();
                case JAPANESE -> month.getYear() + "年" + month.getMonthValue() + "月";
                case CHINESE -> month.getYear() + "年" + month.getMonthValue() + "月";
            };
        }

        public String WEEKDAY_IN_TWO_LETTERS(DayOfWeek day) {
            return switch (lang) {
                case ENGLISH -> day.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).substring(0, 2);
                case GERMAN -> day.getDisplayName(TextStyle.SHORT, Locale.GERMAN).substring(0, 2);
                case SPANISH -> day.getDisplayName(TextStyle.SHORT, Locale.of("es")).substring(0, 2);
                case JAPANESE -> day.getDisplayName(TextStyle.SHORT, Locale.JAPANESE);
                case CHINESE -> day.getDisplayName(TextStyle.SHORT, Locale.CHINESE);
            };
        }

        public String WEEKDAY(DayOfWeek day) {
            return switch (lang) {
                case ENGLISH -> day.getDisplayName(TextStyle.FULL, Locale.ENGLISH);
                case GERMAN -> day.getDisplayName(TextStyle.FULL, Locale.GERMAN);
                case SPANISH -> day.getDisplayName(TextStyle.FULL, Locale.of("es"));
                case JAPANESE -> day.getDisplayName(TextStyle.FULL, Locale.JAPANESE);
                case CHINESE -> day.getDisplayName(TextStyle.FULL, Locale.CHINESE);
            };
        }

        public String DAYS_LISTED(List<String> names) {
            return switch (lang) {
                case ENGLISH -> listedInEnglish(names);
                case GERMAN -> listedInGerman(names);
                case SPANISH -> listedInSpanish(names);
                case JAPANESE -> listedInJapanese(names);
                case CHINESE -> listedInChinese(names);
            };
        }

        public String EVERY_HOUR_ON_THE_HOUR() {
            return switch (lang) {
                case ENGLISH -> "Every hour, on the hour";
                case GERMAN -> "Jede volle Stunde";
                case SPANISH -> "Cada hora en punto";
                case JAPANESE -> "毎時 0 分";
                case CHINESE -> "每小时整点";
            };
        }

        public String EVERY_HOUR_AT_PAST(String minute) {
            return switch (lang) {
                case ENGLISH -> "Every hour at " + minute + " past";
                case GERMAN -> "Stündlich, " + minute + " Minuten nach der vollen Stunde";
                case SPANISH -> "Cada hora, a los " + minute + " minutos";
                case JAPANESE -> "毎時 " + minute + " 分";
                case CHINESE -> "每小时的第 " + minute + " 分钟";
            };
        }

        public String EVERY_DAY_AT(String time) {
            return switch (lang) {
                case ENGLISH -> "Every day at " + time;
                case GERMAN -> "Täglich um " + time;
                case SPANISH -> "Todos los días a " + hourInSpanish(time);
                case JAPANESE -> "毎日 " + time;
                case CHINESE -> "每天 " + time;
            };
        }

        public String EVERY_WEEKDAY_AT(String time) {
            return switch (lang) {
                case ENGLISH -> "Every weekday at " + time;
                case GERMAN -> "Montags bis freitags um " + time;
                case SPANISH -> "De lunes a viernes a " + hourInSpanish(time);
                case JAPANESE -> "平日の " + time;
                case CHINESE -> "工作日 " + time;
            };
        }

        public String EVERY_DAYS_AT(String days, String time) {
            return switch (lang) {
                case ENGLISH -> "Every " + days + " at " + time;
                case GERMAN -> "Jeden " + days + " um " + time;
                case SPANISH -> "Cada " + days + " a " + hourInSpanish(time);
                case JAPANESE -> "毎週" + days + "の " + time;
                case CHINESE -> "每" + days + " " + time;
            };
        }

        public String EVERY_MINUTES(int count) {
            return switch (lang) {
                case ENGLISH -> "Every " + count + " minutes";
                case GERMAN -> "Alle " + count + " Minuten";
                case SPANISH -> "Cada " + count + " minutos";
                case JAPANESE -> count + " 分ごと";
                case CHINESE -> "每 " + count + " 分钟";
            };
        }

        public String EVERY_HOURS_ON_THE_HOUR(int count) {
            return switch (lang) {
                case ENGLISH -> "Every " + count + " hours, on the hour";
                case GERMAN -> "Alle " + count + " Stunden, zur vollen Stunde";
                case SPANISH -> "Cada " + count + " horas, en punto";
                case JAPANESE -> count + " 時間ごと（0 分）";
                case CHINESE -> "每 " + count + " 小时（整点）";
            };
        }

        public String EVERY_HOURS_AT_PAST(int count, String minute) {
            return switch (lang) {
                case ENGLISH -> "Every " + count + " hours, at " + minute + " past";
                case GERMAN -> "Alle " + count + " Stunden, " + minute + " Minuten nach der vollen Stunde";
                case SPANISH -> "Cada " + count + " horas, a los " + minute + " minutos";
                case JAPANESE -> count + " 時間ごと（" + minute + " 分）";
                case CHINESE -> "每 " + count + " 小时（第 " + minute + " 分钟）";
            };
        }

        public String ON_THE_CRON_SCHEDULE(String cron) {
            return switch (lang) {
                case ENGLISH -> "On the cron schedule “" + cron + "”";
                case GERMAN -> "Nach dem cron-Ausdruck „" + cron + "“";
                case SPANISH -> "Según la expresión cron «" + cron + "»";
                case JAPANESE -> "cron 式「" + cron + "」のとおり";
                case CHINESE -> "按 cron 表达式“" + cron + "”";
            };
        }

        public String EVERY_HOUR() {
            return switch (lang) {
                case ENGLISH -> "Every hour";
                case GERMAN -> "Stündlich";
                case SPANISH -> "Cada hora";
                case JAPANESE -> "毎時";
                case CHINESE -> "每小时";
            };
        }

        public String EVERY_DAY() {
            return switch (lang) {
                case ENGLISH -> "Every day";
                case GERMAN -> "Täglich";
                case SPANISH -> "Cada día";
                case JAPANESE -> "毎日";
                case CHINESE -> "每天";
            };
        }

        public String WEEKDAYS() {
            return switch (lang) {
                case ENGLISH -> "Weekdays";
                case GERMAN -> "Mo bis Fr";
                case SPANISH -> "De lunes a viernes";
                case JAPANESE -> "平日";
                case CHINESE -> "工作日";
            };
        }

        public String SOME_DAYS() {
            return switch (lang) {
                case ENGLISH -> "Some days";
                case GERMAN -> "Bestimmte Tage";
                case SPANISH -> "Algunos días";
                case JAPANESE -> "曜日を指定";
                case CHINESE -> "指定星期";
            };
        }

        public String CUSTOM() {
            return switch (lang) {
                case ENGLISH -> "Custom";
                case GERMAN -> "Eigener Ausdruck";
                case SPANISH -> "Personalizado";
                case JAPANESE -> "カスタム";
                case CHINESE -> "自定义";
            };
        }

        public String WRITE_WHAT_THE_GENIE_SHOULD_DO() {
            return switch (lang) {
                case ENGLISH -> "Write what the genie should do.";
                case GERMAN -> "Schreib, was der Dschinn tun soll.";
                case SPANISH -> "Escribe lo que debe hacer el genio.";
                case JAPANESE -> "ジーニーにしてほしいことを書いてください。";
                case CHINESE -> "请写下精灵要做的事。";
            };
        }

        public String A_TASK_CAN_BE_AT_MOST(int most, int has) {
            return switch (lang) {
                case ENGLISH -> "A task can be at most " + String.format("%,d", most) + " characters; this one has " + String.format("%,d", has) + ".";
                case GERMAN -> "Eine Aufgabe darf höchstens " + String.format(Locale.GERMAN, "%,d", most) + " Zeichen lang sein; diese hat " + String.format(Locale.GERMAN, "%,d", has) + ".";
                case SPANISH -> "Una tarea puede tener como mucho " + String.format(Locale.of("es"), "%,d", most) + " caracteres; esta tiene " + String.format(Locale.of("es"), "%,d", has) + ".";
                case JAPANESE -> "タスクは最大 " + String.format("%,d", most) + " 文字までです。いまは " + String.format("%,d", has) + " 文字あります。";
                case CHINESE -> "任务最多 " + String.format("%,d", most) + " 个字符，当前有 " + String.format("%,d", has) + " 个。";
            };
        }

        public String THAT_TIME_HAS_PASSED_PICK_A_LATER_ONE() {
            return switch (lang) {
                case ENGLISH -> "That time has passed. Pick a later one.";
                case GERMAN -> "Diese Zeit ist schon vorbei. Wähle eine spätere.";
                case SPANISH -> "Esa hora ya ha pasado. Elige una más tarde.";
                case JAPANESE -> "その時刻はもう過ぎています。もっと後の時刻を選んでください。";
                case CHINESE -> "这个时间已经过去了，请选择更晚的时间。";
            };
        }

        public String THE_MINUTE_PAST_THE_HOUR_IS_A_NUMBER_FROM_0_TO_59() {
            return switch (lang) {
                case ENGLISH -> "The minute past the hour is a number from 0 to 59.";
                case GERMAN -> "Die Minute nach der vollen Stunde ist eine Zahl von 0 bis 59.";
                case SPANISH -> "El minuto de cada hora es un número del 0 al 59.";
                case JAPANESE -> "分は 0〜59 の数で指定してください。";
                case CHINESE -> "分钟数应为 0 到 59 之间的数字。";
            };
        }

        public String PICK_AT_LEAST_ONE_DAY() {
            return switch (lang) {
                case ENGLISH -> "Pick at least one day.";
                case GERMAN -> "Wähle mindestens einen Tag.";
                case SPANISH -> "Elige al menos un día.";
                case JAPANESE -> "曜日を 1 つ以上選んでください。";
                case CHINESE -> "请至少选择一天。";
            };
        }

        public String WRITE_A_CRON_EXPRESSION() {
            return switch (lang) {
                case ENGLISH -> "Write a cron expression, such as 0 9 * * 1-5.";
                case GERMAN -> "Schreib einen cron-Ausdruck, etwa 0 9 * * 1-5.";
                case SPANISH -> "Escribe una expresión cron, como 0 9 * * 1-5.";
                case JAPANESE -> "cron 式を書いてください。例：0 9 * * 1-5";
                case CHINESE -> "请填写 cron 表达式，例如 0 9 * * 1-5。";
            };
        }

        public String THE_LAST_DAY_HAS_PASSED() {
            return switch (lang) {
                case ENGLISH -> "The last day has passed.";
                case GERMAN -> "Der letzte Tag ist schon vorbei.";
                case SPANISH -> "El último día ya ha pasado.";
                case JAPANESE -> "最終日はもう過ぎています。";
                case CHINESE -> "最后一天已经过去了。";
            };
        }

        public String WRITE_THE_TIME_AS_HOURS_AND_MINUTES() {
            return switch (lang) {
                case ENGLISH -> "Write the time as hours and minutes, such as 09:00.";
                case GERMAN -> "Gib die Zeit in Stunden und Minuten an, etwa 09:00.";
                case SPANISH -> "Escribe la hora con horas y minutos, como 09:00.";
                case JAPANESE -> "時刻は時と分で書いてください。例：09:00";
                case CHINESE -> "请按小时和分钟填写时间，例如 09:00。";
            };
        }

        public String ONCE_AT_FROM_NOW(String day, String time, String fromNow) {
            return switch (lang) {
                case ENGLISH -> "Once, " + day + " at " + time + " — " + fromNow + ".";
                case GERMAN -> "Einmal, " + day + " um " + time + " – " + fromNow + ".";
                case SPANISH -> "Una vez, " + day + " a " + hourInSpanish(time) + " — " + fromNow + ".";
                case JAPANESE -> "1 回だけ、" + day + " " + time + "（" + fromNow + "）。";
                case CHINESE -> "仅一次，" + day + " " + time + "（" + fromNow + "）。";
            };
        }

        public String FIRST_AT(String day, String time) {
            return switch (lang) {
                case ENGLISH -> "first " + day + " at " + time;
                case GERMAN -> "zuerst " + day + " um " + time;
                case SPANISH -> "la primera, " + day + " a " + hourInSpanish(time);
                case JAPANESE -> "初回は" + day + " " + time;
                case CHINESE -> "首次为" + day + " " + time;
            };
        }

        public String BUT_NOT_BEFORE_ITS_LAST_DAY() {
            return switch (lang) {
                case ENGLISH -> "but not before its last day";
                case GERMAN -> "aber kein Lauf mehr vor seinem letzten Tag";
                case SPANISH -> "pero ninguna antes de su último día";
                case JAPANESE -> "ただし、最終日までに実行される回はありません";
                case CHINESE -> "但在结束日期之前不会运行";
            };
        }

        public String OILLAMP_READS_THE_EXPRESSION_WHEN_YOU_SAVE_THE_JOB() {
            return switch (lang) {
                case ENGLISH -> "oillamp reads the expression when you save the job.";
                case GERMAN -> "oillamp liest den Ausdruck, wenn du den Auftrag speicherst.";
                case SPANISH -> "oillamp lee la expresión cuando guardas la tarea.";
                case JAPANESE -> "式は、ジョブを保存するときに oillamp が読み取ります。";
                case CHINESE -> "保存任务时，oillamp 会读取该表达式。";
            };
        }

        public String ONCE_AT(String day, String time) {
            return switch (lang) {
                case ENGLISH -> "Once, " + day + " at " + time;
                case GERMAN -> "Einmal, " + day + " um " + time;
                case SPANISH -> "Una vez, " + day + " a " + hourInSpanish(time);
                case JAPANESE -> "1 回だけ、" + day + " " + time;
                case CHINESE -> "仅一次，" + day + " " + time;
            };
        }

        public String ONCE_WITHOUT_A_TIME() {
            return switch (lang) {
                case ENGLISH -> "Once";
                case GERMAN -> "Einmal";
                case SPANISH -> "Una vez";
                case JAPANESE -> "1 回だけ";
                case CHINESE -> "仅一次";
            };
        }

        public String A_JOB_THAT_HAS_RUN() {
            return switch (lang) {
                case ENGLISH -> "A job that has run";
                case GERMAN -> "Ein gelaufener Auftrag";
                case SPANISH -> "Una tarea ya ejecutada";
                case JAPANESE -> "実行済みのジョブ";
                case CHINESE -> "已运行的任务";
            };
        }

        public String A_JOB() {
            return switch (lang) {
                case ENGLISH -> "A job";
                case GERMAN -> "Ein Auftrag";
                case SPANISH -> "Una tarea";
                case JAPANESE -> "ジョブ";
                case CHINESE -> "任务";
            };
        }

        public String WORKING_ON_IT_SINCE(String time) {
            return switch (lang) {
                case ENGLISH -> "working on it, since " + time;
                case GERMAN -> "arbeitet daran, seit " + time;
                case SPANISH -> "trabajando en ello desde " + hourInSpanish(time);
                case JAPANESE -> time + " から作業中";
                case CHINESE -> "自 " + time + " 起处理中";
            };
        }

        public String NOW_ON_THE_TIMELINE() {
            return switch (lang) {
                case ENGLISH -> "Now";
                case GERMAN -> "Jetzt";
                case SPANISH -> "Ahora";
                case JAPANESE -> "現在";
                case CHINESE -> "现在";
            };
        }

        public String ITS_TIME_CAME_IT_RUNS_AS_SOON_AS_THE_GENIE_IS_AWAKE_AND_FREE() {
            return switch (lang) {
                case ENGLISH -> "its time came; it runs as soon as the genie is awake and free";
                case GERMAN -> "seine Zeit ist gekommen; er läuft, sobald der Dschinn wach und frei ist";
                case SPANISH -> "le ha llegado la hora; se ejecuta en cuanto el genio esté despierto y libre";
                case JAPANESE -> "時刻になりました。ジーニーが起きて手が空きしだい実行されます";
                case CHINESE -> "时间已到；精灵醒着且空闲时就会运行";
            };
        }

        public String TIMES_FROM_TO(int times, String first, String last) {
            return switch (lang) {
                case ENGLISH -> times + " times, " + first + " to " + last;
                case GERMAN -> times + "-mal, " + first + " bis " + last;
                case SPANISH -> times + " veces, de " + first + " a " + last;
                case JAPANESE -> times + " 回、" + first + "〜" + last;
                case CHINESE -> times + " 次，" + first + " 至 " + last;
            };
        }

        public String DONE() {
            return switch (lang) {
                case ENGLISH -> "Done";
                case GERMAN -> "Erledigt";
                case SPANISH -> "Hecha";
                case JAPANESE -> "完了";
                case CHINESE -> "完成";
            };
        }

        public String FAILED() {
            return switch (lang) {
                case ENGLISH -> "Failed";
                case GERMAN -> "Fehlgeschlagen";
                case SPANISH -> "Falló";
                case JAPANESE -> "失敗";
                case CHINESE -> "失败";
            };
        }

        public String RAN_OUT_OF_TIME() {
            return switch (lang) {
                case ENGLISH -> "Ran out of time";
                case GERMAN -> "Zeit abgelaufen";
                case SPANISH -> "Se quedó sin tiempo";
                case JAPANESE -> "時間切れ";
                case CHINESE -> "超时";
            };
        }

        public String STOPPED() {
            return switch (lang) {
                case ENGLISH -> "Stopped";
                case GERMAN -> "Angehalten";
                case SPANISH -> "Detenida";
                case JAPANESE -> "停止済み";
                case CHINESE -> "已停止";
            };
        }
    }

    /// The words of a genie's History page: the moments its home was saved at, its branches, and
    /// going back to a moment.
    public record HistoryPage(Lang lang) {
        public String IS_WAKING_GO_BACK_ONCE_IT_IS_AWAKE(String genie) {
            return switch (lang) {
                case ENGLISH -> genie + " is waking; go back once it is awake";
                case GERMAN -> genie + " wacht auf; geh zurück, sobald er wach ist";
                case SPANISH -> genie + " se está despertando; vuelve atrás cuando esté despierto";
                case JAPANESE -> genie + " が起きようとしています。起きてから戻ってください";
                case CHINESE -> genie + " 正在醒来；请等它醒来后再回退";
            };
        }

        public String IS_WORKING_GO_BACK_ONCE_IT_IS_DONE(String genie) {
            return switch (lang) {
                case ENGLISH -> genie + " is working; go back once it is done";
                case GERMAN -> genie + " arbeitet; geh zurück, sobald er fertig ist";
                case SPANISH -> genie + " está trabajando; vuelve atrás cuando termine";
                case JAPANESE -> genie + " は作業中です。終わってから戻ってください";
                case CHINESE -> genie + " 正在工作；请等它完成后再回退";
            };
        }

        public String A_JOB_IS_RUNNING_GO_BACK_ONCE_IT_IS_DONE() {
            return switch (lang) {
                case ENGLISH -> "A job is running; go back once it is done";
                case GERMAN -> "Ein Auftrag läuft; geh zurück, sobald er fertig ist";
                case SPANISH -> "Hay una tarea en curso; vuelve atrás cuando termine";
                case JAPANESE -> "ジョブを実行中です。終わってから戻ってください";
                case CHINESE -> "有任务正在运行；请等它完成后再回退";
            };
        }

        public String SEND_IT_TO_SLEEP_FIRST_ITS_HOME_CHANGES_ONLY_WHILE_IT_SLEEPS(String genie) {
            return switch (lang) {
                case ENGLISH -> "Send " + genie + " to sleep first: its home changes only while it sleeps.";
                case GERMAN -> "Leg " + genie + " zuerst schlafen: Sein Zuhause ändert sich nur, während er schläft.";
                case SPANISH -> "Duerme primero a " + genie + ": su hogar solo cambia mientras duerme.";
                case JAPANESE -> "先に " + genie + " を眠らせてください。ホームは眠っている間しか変わりません。";
                case CHINESE -> "请先让 " + genie + " 休眠：它的主目录只能在休眠时更改。";
            };
        }

        public String IS_SAVED_ONLY_WHEN_YOU_SAVE_IT_BY_HAND(String genie) {
            return switch (lang) {
                case ENGLISH -> genie + " is saved only when you save it by hand.";
                case GERMAN -> genie + " wird nur gesichert, wenn du es von Hand tust.";
                case SPANISH -> genie + " solo se guarda cuando lo guardas tú a mano.";
                case JAPANESE -> genie + " は手動で保存したときだけ保存されます。";
                case CHINESE -> genie + " 只在你手动保存时才会保存。";
            };
        }

        public String SAVED_WHENEVER(String genie, String when) {
            return switch (lang) {
                case ENGLISH -> "Saved whenever " + genie + " " + when + ".";
                case GERMAN -> "Wird jedes Mal gesichert, wenn " + genie + " " + when + ".";
                case SPANISH -> "Se guarda cada vez que " + genie + " " + when + ".";
                case JAPANESE -> genie + " が" + when + "たびに保存されます。";
                case CHINESE -> "每当 " + genie + " " + when + "时保存。";
            };
        }

        public String GOING_BACK_KEEPS_HOW_IT_IS_NOW_SO_YOU_CAN_ALWAYS_RETURN() {
            return switch (lang) {
                case ENGLISH -> "Going back keeps how it is now, so you can always return.";
                case GERMAN -> "Beim Zurückgehen bleibt der jetzige Stand erhalten, du kannst also jederzeit zurückkehren.";
                case SPANISH -> "Volver atrás conserva cómo está ahora, así que siempre puedes regresar.";
                case JAPANESE -> "戻っても今の状態は残るので、いつでも元に戻れます。";
                case CHINESE -> "回退时会保留当前状态，所以你随时可以回来。";
            };
        }

        public String IS_WORKING_YOU_CAN_GO_BACK_ONCE_IT_IS_DONE(String genie) {
            return switch (lang) {
                case ENGLISH -> genie + " is working. You can go back once it is done.";
                case GERMAN -> genie + " arbeitet. Zurückgehen kannst du, sobald er fertig ist.";
                case SPANISH -> genie + " está trabajando. Podrás volver atrás cuando termine.";
                case JAPANESE -> genie + " は作業中です。終わったら戻れます。";
                case CHINESE -> genie + " 正在工作。等它完成后你就可以回退。";
            };
        }

        public String IS_WAKING_YOU_CAN_GO_BACK_ONCE_IT_IS_AWAKE(String genie) {
            return switch (lang) {
                case ENGLISH -> genie + " is waking. You can go back once it is awake.";
                case GERMAN -> genie + " wacht auf. Zurückgehen kannst du, sobald er wach ist.";
                case SPANISH -> genie + " se está despertando. Podrás volver atrás cuando esté despierto.";
                case JAPANESE -> genie + " が起きようとしています。起きたら戻れます。";
                case CHINESE -> genie + " 正在醒来。等它醒来后你就可以回退。";
            };
        }

        public String HISTORY() {
            return switch (lang) {
                case ENGLISH -> "History";
                case GERMAN -> "Verlauf";
                case SPANISH -> "Historial";
                case JAPANESE -> "履歴";
                case CHINESE -> "历史";
            };
        }

        public String NEW_BRANCH_ELLIPSIS() {
            return switch (lang) {
                case ENGLISH -> "New branch…";
                case GERMAN -> "Neuer Zweig…";
                case SPANISH -> "Nueva rama…";
                case JAPANESE -> "新しいブランチ…";
                case CHINESE -> "新建分支…";
            };
        }

        public String KEEP_HOW_IT_WAS_LAST_SAVED_AS_A_BRANCH_OF_ITS_OWN(String genie) {
            return switch (lang) {
                case ENGLISH ->
                    "Keep how " + genie + " was last saved as a branch of "
                    + "its own, to try something else from there. " + genie + " stays where it lives";
                case GERMAN -> "Den zuletzt gesicherten Stand von " + genie + " als eigenen Zweig behalten, um von dort aus etwas anderes zu versuchen. " + genie + " bleibt, wo er zu Hause ist";
                case SPANISH -> "Conservar cómo se guardó " + genie + " por última vez como una rama propia, para probar otra cosa desde ahí. " + genie + " se queda donde vive";
                case JAPANESE -> genie + " を最後に保存した状態を別のブランチとして残し、そこから別のことを試せるようにします。" + genie + " は今のブランチにいたままです";
                case CHINESE -> "将 " + genie + " 最近一次保存的状态保留为单独的分支，以便从那里尝试别的做法。" + genie + " 仍留在原来的分支上";
            };
        }

        public String SAVE_NOW_ELLIPSIS() {
            return switch (lang) {
                case ENGLISH -> "Save now…";
                case GERMAN -> "Jetzt sichern…";
                case SPANISH -> "Guardar ahora…";
                case JAPANESE -> "今すぐ保存…";
                case CHINESE -> "立即保存…";
            };
        }

        public String KEEP_HOW_THE_GENIE_IS_NOW_AS_A_MOMENT_TO_COME_BACK_TO() {
            return switch (lang) {
                case ENGLISH -> "Keep how the genie is now as a moment to come back to, such as before a big change";
                case GERMAN -> "Den jetzigen Stand des Dschinns festhalten, um später dorthin zurückzukehren, etwa vor einer großen Änderung";
                case SPANISH -> "Conservar cómo está el genio ahora como un momento al que volver, por ejemplo antes de un cambio grande";
                case JAPANESE -> "ジーニーの今の状態を、あとで戻れる時点として残します。大きな変更の前などに";
                case CHINESE -> "将精灵当前的状态保留为可以回来的保存点，例如在做大改动之前";
            };
        }

        public String HOW_IT_WAS_BEFORE_IS_KEPT_SO_YOU_CAN_RETURN_TO_IT() {
            return switch (lang) {
                case ENGLISH -> "How it was before is kept, so you can return to it.";
                case GERMAN -> "Der vorherige Stand bleibt erhalten, du kannst also dorthin zurückkehren.";
                case SPANISH -> "Cómo estaba antes se conserva, así que puedes volver a ello.";
                case JAPANESE -> "以前の状態は残っているので、そこに戻れます。";
                case CHINESE -> "之前的状态已保留，你可以回到那里。";
            };
        }

        public String UNDO() {
            return switch (lang) {
                case ENGLISH -> "Undo";
                case GERMAN -> "Rückgängig";
                case SPANISH -> "Deshacer";
                case JAPANESE -> "元に戻す";
                case CHINESE -> "撤销";
            };
        }

        public String GO_BACK_TO_HOW_IT_WAS_BEFORE() {
            return switch (lang) {
                case ENGLISH -> "Go back to how it was before";
                case GERMAN -> "Zum vorherigen Stand zurückkehren";
                case SPANISH -> "Volver a cómo estaba antes";
                case JAPANESE -> "以前の状態に戻す";
                case CHINESE -> "回到之前的状态";
            };
        }

        public String NAMES_LISTED(List<String> names) {
            return switch (lang) {
                case ENGLISH -> listedInEnglish(names);
                case GERMAN -> listedInGerman(names);
                case SPANISH -> listedInSpanish(names);
                case JAPANESE -> listedInJapanese(names);
                case CHINESE -> listedInChinese(names);
            };
        }

        public String THEN() {
            return switch (lang) {
                case ENGLISH -> "then";
                case GERMAN -> "dann";
                case SPANISH -> "luego";
                case JAPANESE -> "その後";
                case CHINESE -> "然后";
            };
        }

        public String WHAT_CHANGED_SINCE_THE_NEWEST_MOMENT_WAS_NEVER_SAVED_AND_IS_LOST(String genie) {
            return switch (lang) {
                case ENGLISH -> "What changed in " + genie + "'s home since the newest moment was never saved, and is lost.";
                case GERMAN -> "Was sich im Zuhause von " + genie + " seit dem neuesten Stand geändert hat, wurde nie gesichert und ist verloren.";
                case SPANISH -> "Lo que cambió en el hogar de " + genie + " desde el momento más reciente nunca se guardó, y se pierde.";
                case JAPANESE -> "最新の時点以降に " + genie + " のホームで変わった内容は保存されておらず、失われます。";
                case CHINESE -> genie + " 的主目录自最新保存点以来的更改从未保存，将会丢失。";
            };
        }

        public String RESET_TO_HOW_IT_WAS(String branch, String when) {
            return switch (lang) {
                case ENGLISH -> "Reset " + branch + " to how it was " + when + "?";
                case GERMAN -> branch + " auf den Stand zurücksetzen, wie er " + when + " war?";
                case SPANISH -> "¿Restablecer " + branch + " a como estaba " + when + "?";
                case JAPANESE -> branch + " を" + when + "の状態にリセットしますか？";
                case CHINESE -> "要将 " + branch + " 重置为" + when + "时的状态吗？";
            };
        }

        public String MOMENTS_AFTER_IT_LEAVE(int count, String branch) {
            return switch (lang) {
                case ENGLISH ->
                    count == 0 ? "No moment comes after it on " + branch + "."
                    : count == 1 ? "The moment after it leaves " + branch + "."
                    : "The " + count + " moments after it leave " + branch + ".";
                case GERMAN -> count == 0 ? "Auf " + branch + " folgt danach kein Stand mehr." : count == 1 ? "Der Stand danach verschwindet von " + branch + "." : "Die " + count + " Stände danach verschwinden von " + branch + ".";
                case SPANISH -> count == 0 ? "No hay ningún momento después en " + branch + "." : count == 1 ? "El momento siguiente sale de " + branch + "." : "Los " + count + " momentos siguientes salen de " + branch + ".";
                case JAPANESE -> count == 0 ? branch + " には、この後の時点はありません。" : "この後の " + count + " 個の時点が " + branch + " から外れます。";
                case CHINESE -> count == 0 ? branch + " 上在它之后没有保存点。" : "它之后的 " + count + " 个保存点将从 " + branch + " 上移除。";
            };
        }

        public String A_RESET_KEEPS_NO_COPY_SO_IT_CANNOT_BE_UNDONE() {
            return switch (lang) {
                case ENGLISH ->
                    "A reset keeps no copy, so it cannot be undone; moments also on another branch stay there. "
                    + "Going back instead keeps how it is now as a moment of its own.";
                case GERMAN -> "Beim Zurücksetzen bleibt keine Kopie, es lässt sich also nicht rückgängig machen; Stände, die auch auf einem anderen Zweig liegen, bleiben dort. Zurückgehen dagegen hält den jetzigen Stand als eigenen Stand fest.";
                case SPANISH -> "Restablecer no guarda ninguna copia, así que no se puede deshacer; los momentos que también están en otra rama se quedan allí. Volver atrás, en cambio, conserva cómo está ahora como un momento propio.";
                case JAPANESE -> "リセットはコピーを残さないため、元に戻せません。ほかのブランチにもある時点は、そこに残ります。代わりに「戻る」を使えば、今の状態が独立した時点として残ります。";
                case CHINESE -> "重置不会保留副本，因此无法撤销；同时也在其他分支上的保存点会保留在那里。而回退则会把当前状态另存为一个保存点。";
            };
        }

        public String RESET(String branch) {
            return switch (lang) {
                case ENGLISH -> "Reset " + branch;
                case GERMAN -> branch + " zurücksetzen";
                case SPANISH -> "Restablecer " + branch;
                case JAPANESE -> branch + " をリセット";
                case CHINESE -> "重置 " + branch;
            };
        }

        public String DELETE_THE_BRANCH(String branch) {
            return switch (lang) {
                case ENGLISH -> "Delete the branch " + branch + "?";
                case GERMAN -> "Den Zweig " + branch + " löschen?";
                case SPANISH -> "¿Eliminar la rama " + branch + "?";
                case JAPANESE -> "ブランチ " + branch + " を削除しますか？";
                case CHINESE -> "要删除分支 " + branch + " 吗？";
            };
        }

        public String MOMENTS_ON_IT_ALONE_GO_WITH_IT(int count, String branch) {
            return switch (lang) {
                case ENGLISH ->
                    count == 0 ? "Every moment on it is on another branch too, so no moment is lost."
                    : count == 1 ? "One moment is on " + branch + " alone, and goes with it."
                    : count + " moments are on " + branch + " alone, and go with it.";
                case GERMAN -> count == 0 ? "Jeder Stand darauf liegt auch auf einem anderen Zweig, es geht also keiner verloren." : count == 1 ? "Ein Stand liegt nur auf " + branch + " und verschwindet mit ihm." : count + " Stände liegen nur auf " + branch + " und verschwinden mit ihm.";
                case SPANISH -> count == 0 ? "Todos sus momentos están también en otra rama, así que no se pierde ninguno." : count == 1 ? "Un momento solo está en " + branch + ", y se va con ella." : count + " momentos solo están en " + branch + ", y se van con ella.";
                case JAPANESE -> count == 0 ? "このブランチの時点はすべてほかのブランチにもあるので、失われる時点はありません。" : branch + " にしかない時点が " + count + " 個あり、ブランチと一緒に削除されます。";
                case CHINESE -> count == 0 ? "它上面的每个保存点也都在其他分支上，因此不会丢失任何保存点。" : "有 " + count + " 个保存点只在 " + branch + " 上，会随它一起删除。";
            };
        }

        public String DELETE(String branch) {
            return switch (lang) {
                case ENGLISH -> "Delete " + branch;
                case GERMAN -> branch + " löschen";
                case SPANISH -> "Eliminar " + branch;
                case JAPANESE -> branch + " を削除";
                case CHINESE -> "删除 " + branch;
            };
        }

        public String DELETE_THE_OLDEST_MOMENTS(int count) {
            return switch (lang) {
                case ENGLISH -> "Delete the " + (count == 1 ? "oldest moment" : count + " oldest moments") + "?";
                case GERMAN -> count == 1 ? "Den ältesten Stand löschen?" : "Die " + count + " ältesten Stände löschen?";
                case SPANISH -> count == 1 ? "¿Eliminar el momento más antiguo?" : "¿Eliminar los " + count + " momentos más antiguos?";
                case JAPANESE -> count == 1 ? "最も古い時点を削除しますか？" : "最も古い " + count + " 個の時点を削除しますか？";
                case CHINESE -> count == 1 ? "要删除最早的保存点吗？" : "要删除最早的 " + count + " 个保存点吗？";
            };
        }

        public String THEY_DO_NOT_FIT_WITHIN_HOW_FAR_THE_HISTORY_MAY_GROW(int count, String branches, String frees) {
            return switch (lang) {
                case ENGLISH ->
                    (count == 1 ? "It does" : "They do") + " not fit within how far the history may grow"
                    + (branches.isEmpty() ? "" : ", on " + branches) + ". Deleting "
                    + (count == 1 ? "it" : "them") + " frees " + frees
                    + ". Each branch keeps its newest moment, and the next save would delete them anyway.";
                case GERMAN -> (count == 1 ? "Er passt" : "Sie passen") + " nicht mehr in den Platz, den der Verlauf belegen darf" + (branches.isEmpty() ? "" : " (auf " + branches + ")") + ". Das Löschen gibt " + frees + " frei. Jeder Zweig behält seinen neuesten Stand, und die nächste Sicherung würde " + (count == 1 ? "ihn" : "sie") + " ohnehin löschen.";
                case SPANISH -> (count == 1 ? "No cabe" : "No caben") + " en el espacio que puede ocupar el historial" + (branches.isEmpty() ? "" : ", en " + branches) + ". " + (count == 1 ? "Eliminarlo" : "Eliminarlos") + " libera " + frees + ". Cada rama conserva su momento más reciente, y el próximo guardado " + (count == 1 ? "lo" : "los") + " eliminaría de todos modos.";
                case JAPANESE -> "履歴に許された容量に収まりません" + (branches.isEmpty() ? "" : "（" + branches + "）") + "。削除すると " + frees + " 空きます。各ブランチの最新の時点は残ります。削除しなくても、次に保存するときに削除されます。";
                case CHINESE -> (count == 1 ? "它" : "它们") + "超出了历史允许占用的空间" + (branches.isEmpty() ? "" : "（位于 " + branches + "）") + "。删除后可释放 " + frees + "。每个分支都会保留最新的保存点，而且下次保存时也会删除" + (count == 1 ? "它" : "它们") + "。";
            };
        }

        public String DELETE_THEM(int count) {
            return switch (lang) {
                case ENGLISH -> "Delete " + (count == 1 ? "it" : "them");
                case GERMAN -> count == 1 ? "Ihn löschen" : "Sie löschen";
                case SPANISH -> count == 1 ? "Eliminarlo" : "Eliminarlos";
                case JAPANESE -> "削除する";
                case CHINESE -> "删除";
            };
        }

        public String CANCEL() {
            return switch (lang) {
                case ENGLISH -> "Cancel";
                case GERMAN -> "Abbrechen";
                case SPANISH -> "Cancelar";
                case JAPANESE -> "キャンセル";
                case CHINESE -> "取消";
            };
        }

        public String GO_BACK_INSTEAD() {
            return switch (lang) {
                case ENGLISH -> "Go back instead";
                case GERMAN -> "Lieber zurückgehen";
                case SPANISH -> "Mejor volver atrás";
                case JAPANESE -> "代わりに戻る";
                case CHINESE -> "改为回退";
            };
        }

        public String BRING_IT_BACK_TO_THAT_MOMENT_AND_KEEP_HOW_IT_IS_NOW() {
            return switch (lang) {
                case ENGLISH -> "Bring it back to that moment, and keep how it is now as a moment you can return to";
                case GERMAN -> "Zu diesem Stand zurückkehren und den jetzigen als Stand behalten, zu dem du zurückkannst";
                case SPANISH -> "Devolverlo a ese momento y conservar cómo está ahora como un momento al que puedes volver";
                case JAPANESE -> "その時点に戻し、今の状態は戻れる時点として残します";
                case CHINESE -> "回到那个保存点，并把当前状态保留为可以回来的保存点";
            };
        }

        public String SHOW_ONLY_THE_NEWEST_MOMENTS() {
            return switch (lang) {
                case ENGLISH -> "Show only the newest moments";
                case GERMAN -> "Nur die neuesten Stände zeigen";
                case SPANISH -> "Mostrar solo los momentos más recientes";
                case JAPANESE -> "最新の時点だけを表示";
                case CHINESE -> "只显示最新的保存点";
            };
        }

        public String SHOW_EARLIER_MOMENTS(int count) {
            return switch (lang) {
                case ENGLISH -> "Show " + count + " earlier " + (count == 1 ? "moment" : "moments");
                case GERMAN -> count + (count == 1 ? " früheren Stand zeigen" : " frühere Stände zeigen");
                case SPANISH -> "Mostrar " + count + (count == 1 ? " momento anterior" : " momentos anteriores");
                case JAPANESE -> "以前の時点を " + count + " 個表示";
                case CHINESE -> "显示更早的 " + count + " 个保存点";
            };
        }

        public String MOMENTS() {
            return switch (lang) {
                case ENGLISH -> "MOMENTS";
                case GERMAN -> "STÄNDE";
                case SPANISH -> "MOMENTOS";
                case JAPANESE -> "時点";
                case CHINESE -> "保存点";
            };
        }

        public String READING_THE_HISTORY() {
            return switch (lang) {
                case ENGLISH -> "Reading the history…";
                case GERMAN -> "Lese den Verlauf…";
                case SPANISH -> "Leyendo el historial…";
                case JAPANESE -> "履歴を読み込み中…";
                case CHINESE -> "正在读取历史…";
            };
        }

        public String NOTHING_SAVED_YET() {
            return switch (lang) {
                case ENGLISH -> "Nothing saved yet";
                case GERMAN -> "Noch nichts gesichert";
                case SPANISH -> "Aún no hay nada guardado";
                case JAPANESE -> "まだ何も保存されていません";
                case CHINESE -> "还没有保存任何内容";
            };
        }

        public String ITS_HOME_IS_SAVED_THE_FIRST_TIME_IT_WAKES(String genie) {
            return switch (lang) {
                case ENGLISH ->
                    genie + "'s home is saved the first time it wakes, and from then on "
                    + "whenever something changed. Each save is a moment you can bring it back to.";
                case GERMAN -> "Das Zuhause von " + genie + " wird beim ersten Aufwachen gesichert und danach immer, wenn sich etwas geändert hat. Jede Sicherung ist ein Stand, zu dem du ihn zurückbringen kannst.";
                case SPANISH -> "El hogar de " + genie + " se guarda la primera vez que despierta y, a partir de ahí, cada vez que algo cambia. Cada guardado es un momento al que puedes devolverlo.";
                case JAPANESE -> genie + " のホームは初めて起きたときに保存され、その後は何かが変わるたびに保存されます。保存した時点には、いつでも戻せます。";
                case CHINESE -> genie + " 的主目录会在它第一次醒来时保存，此后每当有变化时都会保存。每次保存都是一个可以回到的保存点。";
            };
        }

        public String HOW_IT_IS_NOW_ON_BRANCH(String genie, String branch) {
            return switch (lang) {
                case ENGLISH -> "How " + genie + " is now" + (branch.isEmpty() ? "" : ", on " + branch);
                case GERMAN -> "So ist " + genie + " jetzt" + (branch.isEmpty() ? "" : ", auf " + branch);
                case SPANISH -> "Cómo está " + genie + " ahora" + (branch.isEmpty() ? "" : ", en " + branch);
                case JAPANESE -> genie + " の現在の状態" + (branch.isEmpty() ? "" : "（" + branch + "）");
                case CHINESE -> genie + " 的当前状态" + (branch.isEmpty() ? "" : "（" + branch + "）");
            };
        }

        public String NOW() {
            return switch (lang) {
                case ENGLISH -> "now";
                case GERMAN -> "jetzt";
                case SPANISH -> "ahora";
                case JAPANESE -> "現在";
                case CHINESE -> "现在";
            };
        }

        public String LIVES_HERE(String genie) {
            return switch (lang) {
                case ENGLISH -> "(" + genie + " lives here)";
                case GERMAN -> "(" + genie + " ist hier zu Hause)";
                case SPANISH -> "(" + genie + " vive aquí)";
                case JAPANESE -> "（" + genie + " はここにいます）";
                case CHINESE -> "（" + genie + " 位于此处）";
            };
        }

        public String AND_MORE(int count) {
            return switch (lang) {
                case ENGLISH -> "and " + count + " more";
                case GERMAN -> "und " + count + " weitere";
                case SPANISH -> "y " + count + " más";
                case JAPANESE -> "ほか " + count + " 個";
                case CHINESE -> "以及另外 " + count + " 个";
            };
        }

        public String CLICK_A_LINE_TO_SHOW_IT_HERE() {
            return switch (lang) {
                case ENGLISH -> "Click a line to show it here";
                case GERMAN -> "Klicke auf eine Linie, um sie hier zu zeigen";
                case SPANISH -> "Haz clic en una línea para mostrarla aquí";
                case JAPANESE -> "線をクリックすると、ここに表示します";
                case CHINESE -> "点击一条线即可在此显示";
            };
        }

        public String STAND_HERE(List<String> branches) {
            return switch (lang) {
                case ENGLISH -> listedInEnglish(branches) + (branches.size() == 1 ? " stands here" : " stand here");
                case GERMAN -> (branches.size() == 1 ? "Hier steht " : "Hier stehen ") + listedInGerman(branches);
                case SPANISH -> listedInSpanish(branches) + (branches.size() == 1 ? " está aquí" : " están aquí");
                case JAPANESE -> listedInJapanese(branches) + " がここにあります";
                case CHINESE -> listedInChinese(branches) + " 在这里";
            };
        }

        public String CLICK_AGAIN_TO_CLOSE() {
            return switch (lang) {
                case ENGLISH -> "Click again to close";
                case GERMAN -> "Nochmals klicken zum Schließen";
                case SPANISH -> "Haz clic otra vez para cerrar";
                case JAPANESE -> "もう一度クリックで閉じる";
                case CHINESE -> "再次点击关闭";
            };
        }

        public String CLICK_TO_GO_BACK_TO_THIS_MOMENT_START_A_BRANCH_FROM_IT_OR_OPEN_ITS_CONVERSATION() {
            return switch (lang) {
                case ENGLISH -> "Click to go back to this moment, start a branch from it, or open its conversation";
                case GERMAN -> "Klicken, um zu diesem Stand zurückzukehren, einen Zweig davon abzuleiten oder sein Gespräch zu öffnen";
                case SPANISH -> "Haz clic para volver a este momento, empezar una rama desde él o abrir su conversación";
                case JAPANESE -> "クリックすると、この時点に戻る、ここからブランチを作る、この時点の会話を開く、のいずれかを選べます";
                case CHINESE -> "点击可回到此保存点、从这里创建分支，或打开它的对话";
            };
        }

        public String GO_BACK_TO_THIS_MOMENT_ELLIPSIS() {
            return switch (lang) {
                case ENGLISH -> "Go back to this moment…";
                case GERMAN -> "Zu diesem Stand zurück…";
                case SPANISH -> "Volver a este momento…";
                case JAPANESE -> "この時点に戻る…";
                case CHINESE -> "回到此保存点…";
            };
        }

        public String BRING_ITS_FILES_AND_CONVERSATIONS_BACK_TO_HOW_THEY_WERE_THEN() {
            return switch (lang) {
                case ENGLISH -> "Bring its files and conversations back to how they were then";
                case GERMAN -> "Seine Dateien und Gespräche auf den damaligen Stand zurückbringen";
                case SPANISH -> "Devolver sus archivos y conversaciones a como estaban entonces";
                case JAPANESE -> "ファイルと会話を当時の状態に戻します";
                case CHINESE -> "将它的文件和对话恢复到当时的样子";
            };
        }

        public String START_A_BRANCH_HERE_ELLIPSIS() {
            return switch (lang) {
                case ENGLISH -> "Start a branch here…";
                case GERMAN -> "Hier einen Zweig beginnen…";
                case SPANISH -> "Empezar una rama aquí…";
                case JAPANESE -> "ここからブランチを作成…";
                case CHINESE -> "从这里创建分支…";
            };
        }

        public String KEEP_THIS_MOMENT_AS_A_BRANCH_OF_ITS_OWN(String genie) {
            return switch (lang) {
                case ENGLISH -> "Keep this moment as a branch of its own, to try something else from here. " + genie + " stays where it lives";
                case GERMAN -> "Diesen Stand als eigenen Zweig behalten, um von hier aus etwas anderes zu versuchen. " + genie + " bleibt, wo er zu Hause ist";
                case SPANISH -> "Conservar este momento como una rama propia, para probar otra cosa desde aquí. " + genie + " se queda donde vive";
                case JAPANESE -> "この時点を別のブランチとして残し、ここから別のことを試せるようにします。" + genie + " は今のブランチにいたままです";
                case CHINESE -> "将此保存点保留为单独的分支，以便从这里尝试别的做法。" + genie + " 仍留在原来的分支上";
            };
        }

        public String RESET_TO_HERE_ELLIPSIS() {
            return switch (lang) {
                case ENGLISH -> "Reset to here…";
                case GERMAN -> "Hierher zurücksetzen…";
                case SPANISH -> "Restablecer hasta aquí…";
                case JAPANESE -> "ここまでリセット…";
                case CHINESE -> "重置到这里…";
            };
        }

        public String TAKE_EVERY_MOMENT_AFTER_THIS_ONE_OFF_THE_BRANCH_FOR_GOOD() {
            return switch (lang) {
                case ENGLISH -> "Take every moment after this one off the branch, for good. You are asked first";
                case GERMAN -> "Alle Stände nach diesem endgültig vom Zweig nehmen. Du wirst vorher gefragt";
                case SPANISH -> "Quitar de la rama, para siempre, todos los momentos posteriores a este. Antes se te pregunta";
                case JAPANESE -> "この時点より後の時点をすべて、ブランチから完全に外します。実行前に確認があります";
                case CHINESE -> "将此保存点之后的所有保存点从分支上永久移除。操作前会先询问你";
            };
        }

        public String OPEN_THE_CONVERSATION() {
            return switch (lang) {
                case ENGLISH -> "Open the conversation";
                case GERMAN -> "Gespräch öffnen";
                case SPANISH -> "Abrir la conversación";
                case JAPANESE -> "会話を開く";
                case CHINESE -> "打开对话";
            };
        }

        public String LIVES_ON_IT_AND_EVERY_SAVE_GOES_THERE(String genie, String branch) {
            return switch (lang) {
                case ENGLISH -> genie + " lives on " + branch + ", and every save goes there. Click another branch, or its line, to look at it.";
                case GERMAN -> genie + " ist auf " + branch + " zu Hause, und jede Sicherung landet dort. Klicke auf einen anderen Zweig oder seine Linie, um ihn anzusehen.";
                case SPANISH -> genie + " vive en " + branch + ", y cada guardado va allí. Haz clic en otra rama, o en su línea, para verla.";
                case JAPANESE -> genie + " は " + branch + " にいて、保存はすべてそこに入ります。ほかのブランチやその線をクリックすると、そのブランチを見られます。";
                case CHINESE -> genie + " 位于 " + branch + " 上，每次保存都会存到那里。点击其他分支或它的线即可查看。";
            };
        }

        public String YOU_ARE_LOOKING_AT(String shown, String genie, String home) {
            return switch (lang) {
                case ENGLISH -> "You are looking at " + shown + ". " + genie + " lives on " + home + ", and stays there until you move it.";
                case GERMAN -> "Du siehst dir " + shown + " an. " + genie + " ist auf " + home + " zu Hause und bleibt dort, bis du ihn umziehen lässt.";
                case SPANISH -> "Estás viendo " + shown + ". " + genie + " vive en " + home + ", y se queda allí hasta que lo muevas.";
                case JAPANESE -> shown + " を表示しています。" + genie + " は " + home + " にいて、あなたが移すまでそこにいます。";
                case CHINESE -> "你正在查看 " + shown + "。" + genie + " 位于 " + home + " 上，在你移动它之前会一直留在那里。";
            };
        }

        public String MOVE_TO_ELLIPSIS(String genie, String branch) {
            return switch (lang) {
                case ENGLISH -> "Move " + genie + " to " + branch + "…";
                case GERMAN -> genie + " nach " + branch + " umziehen lassen…";
                case SPANISH -> "Mover a " + genie + " a " + branch + "…";
                case JAPANESE -> genie + " を " + branch + " に移す…";
                case CHINESE -> "将 " + genie + " 移到 " + branch + "…";
            };
        }

        public String MAKE_THIS_BRANCH_THE_GENIES_HOME() {
            return switch (lang) {
                case ENGLISH -> "Make this branch the genie's home: its files and conversations become this branch's newest moment";
                case GERMAN -> "Diesen Zweig zum Zuhause des Dschinns machen: Seine Dateien und Gespräche werden zum neuesten Stand dieses Zweigs";
                case SPANISH -> "Hacer de esta rama el hogar del genio: sus archivos y conversaciones pasan a ser los del momento más reciente de esta rama";
                case JAPANESE -> "このブランチをジーニーの居場所にします。ファイルと会話は、このブランチの最新の時点のものになります";
                case CHINESE -> "让精灵移到这个分支：它的文件和对话将变成此分支最新保存点的内容";
            };
        }

        public String DELETE_ELLIPSIS(String branch) {
            return switch (lang) {
                case ENGLISH -> "Delete " + branch + "…";
                case GERMAN -> branch + " löschen…";
                case SPANISH -> "Eliminar " + branch + "…";
                case JAPANESE -> branch + " を削除…";
                case CHINESE -> "删除 " + branch + "…";
            };
        }

        public String DELETE_THIS_BRANCH_YOU_ARE_TOLD_FIRST_HOW_MANY_MOMENTS_ONLY_IT_HOLDS() {
            return switch (lang) {
                case ENGLISH -> "Delete this branch. You are told first how many moments only it holds";
                case GERMAN -> "Diesen Zweig löschen. Vorher erfährst du, wie viele Stände nur auf ihm liegen";
                case SPANISH -> "Eliminar esta rama. Antes se te dice cuántos momentos solo están en ella";
                case JAPANESE -> "このブランチを削除します。その前に、このブランチにしかない時点がいくつあるかを表示します";
                case CHINESE -> "删除此分支。删除前会告诉你有多少保存点只在它上面";
            };
        }

        public String LIVES_ON_THIS_BRANCH(String genie) {
            return switch (lang) {
                case ENGLISH -> genie + " lives on this branch.";
                case GERMAN -> genie + " ist auf diesem Zweig zu Hause.";
                case SPANISH -> genie + " vive en esta rama.";
                case JAPANESE -> genie + " はこのブランチにいます。";
                case CHINESE -> genie + " 位于此分支上。";
            };
        }

        public String ITS_LINE_IS_THE_ONE_WITH_WORDS() {
            return switch (lang) {
                case ENGLISH -> "Its line is the one with words";
                case GERMAN -> "Seine Linie ist die beschriftete";
                case SPANISH -> "Su línea es la que tiene texto";
                case JAPANESE -> "文字の付いた線がそのブランチです";
                case CHINESE -> "带文字的那条线就是它";
            };
        }

        public String CLICK_TO_LOOK_AT_ITS_LINE() {
            return switch (lang) {
                case ENGLISH -> "Click to look at its line";
                case GERMAN -> "Klicken, um seine Linie anzusehen";
                case SPANISH -> "Haz clic para ver su línea";
                case JAPANESE -> "クリックでその線を表示";
                case CHINESE -> "点击查看它的线";
            };
        }

        public String WAKE_IT_ONCE_FIRST_ITS_SETTINGS_ARE_KEPT_IN_ITS_LAMP(String genie) {
            return switch (lang) {
                case ENGLISH -> "Wake " + genie + " once first. Its settings are kept in its lamp, which it gets as it first wakes.";
                case GERMAN -> "Wecke " + genie + " zuerst einmal. Seine Einstellungen liegen in seiner Lampe, und die bekommt er beim ersten Aufwachen.";
                case SPANISH -> "Despierta primero a " + genie + " una vez. Sus ajustes se guardan en su lámpara, que recibe la primera vez que despierta.";
                case JAPANESE -> "まず一度 " + genie + " を起こしてください。設定はランプに保存されますが、ランプは初めて起きたときに作られます。";
                case CHINESE -> "请先唤醒 " + genie + " 一次。它的设置保存在它的神灯里，而神灯要在它第一次醒来时才会创建。";
            };
        }

        public String READING_ITS_SETTINGS() {
            return switch (lang) {
                case ENGLISH -> "Reading its settings…";
                case GERMAN -> "Lese seine Einstellungen…";
                case SPANISH -> "Leyendo sus ajustes…";
                case JAPANESE -> "設定を読み込み中…";
                case CHINESE -> "正在读取它的设置…";
            };
        }

        public String SEND_IT_TO_SLEEP_TO_CHANGE_THESE(String genie) {
            return switch (lang) {
                case ENGLISH -> "Send " + genie + " to sleep to change these. While it is awake, it keeps what it woke with.";
                case GERMAN -> "Leg " + genie + " schlafen, um das zu ändern. Solange er wach ist, bleibt es so, wie es beim Aufwachen war.";
                case SPANISH -> "Duerme a " + genie + " para cambiar esto. Mientras está despierto, mantiene lo que tenía al despertar.";
                case JAPANESE -> "変更するには " + genie + " を眠らせてください。起きている間は、起きたときの設定のままです。";
                case CHINESE -> "要修改这些设置，请先让 " + genie + " 休眠。它醒着时会一直沿用醒来时的设置。";
            };
        }

        public String WHEN_IT_SAVES_BY_ITSELF() {
            return switch (lang) {
                case ENGLISH -> "WHEN IT SAVES BY ITSELF";
                case GERMAN -> "WANN ER VON SELBST SICHERT";
                case SPANISH -> "CUÁNDO SE GUARDA SOLO";
                case JAPANESE -> "自動で保存するタイミング";
                case CHINESE -> "自动保存的时机";
            };
        }

        public String AS_IT_WAKES() {
            return switch (lang) {
                case ENGLISH -> "As it wakes";
                case GERMAN -> "Beim Aufwachen";
                case SPANISH -> "Al despertar";
                case JAPANESE -> "起きたとき";
                case CHINESE -> "醒来时";
            };
        }

        public String AFTER_IT_WENT_TO_SLEEP() {
            return switch (lang) {
                case ENGLISH -> "After it went to sleep";
                case GERMAN -> "Nachdem er eingeschlafen ist";
                case SPANISH -> "Después de dormirse";
                case JAPANESE -> "眠った後";
                case CHINESE -> "休眠后";
            };
        }

        public String AS_IT_BEGINS_ON_A_QUESTION_OR_A_JOB() {
            return switch (lang) {
                case ENGLISH -> "As it begins on a question or a job";
                case GERMAN -> "Wenn er sich an eine Frage oder einen Auftrag macht";
                case SPANISH -> "Al empezar con una pregunta o una tarea";
                case JAPANESE -> "質問やジョブに取りかかるとき";
                case CHINESE -> "开始处理问题或任务时";
            };
        }

        public String AS_IT_ANSWERS_OR_ENDS_A_JOB() {
            return switch (lang) {
                case ENGLISH -> "As it answers, or ends a job";
                case GERMAN -> "Wenn er antwortet oder einen Auftrag beendet";
                case SPANISH -> "Al responder, o al terminar una tarea";
                case JAPANESE -> "答えたとき、またはジョブを終えたとき";
                case CHINESE -> "回答时，或完成任务时";
            };
        }

        public String A_JOB_IS_TOLD_WHAT_ITS_LAST_RUNS_SAID_AND_CHANGED() {
            return switch (lang) {
                case ENGLISH ->
                    "A job is told what its last runs said and changed from "
                    + "what was saved as they ended. Saving by hand works whatever these say.";
                case GERMAN -> "Ein Auftrag erfährt, was seine letzten Läufe gesagt und geändert haben, aus dem, was an ihrem Ende gesichert wurde. Von Hand sichern geht immer, ganz gleich, was hier eingestellt ist.";
                case SPANISH -> "A una tarea se le cuenta lo que dijeron y cambiaron sus últimas ejecuciones, a partir de lo que se guardó al terminar cada una. Guardar a mano funciona sea cual sea esta configuración.";
                case JAPANESE -> "ジョブには、直近の実行が何を言い、何を変えたかが、終了時に保存された内容をもとに伝えられます。手動での保存は、ここの設定に関係なくいつでもできます。";
                case CHINESE -> "任务会根据最近几次运行结束时保存的内容，了解它们说了什么、改了什么。无论这里如何设置，都可以随时手动保存。";
            };
        }

        public String HOW_LARGE_IT_MAY_GROW() {
            return switch (lang) {
                case ENGLISH -> "HOW LARGE IT MAY GROW";
                case GERMAN -> "WIE GROSS ER WERDEN DARF";
                case SPANISH -> "CUÁNTO PUEDE CRECER";
                case JAPANESE -> "履歴の上限サイズ";
                case CHINESE -> "历史可占用的空间";
            };
        }

        public String WHAT_A_MOMENT_HOLDS() {
            return switch (lang) {
                case ENGLISH -> "WHAT A MOMENT HOLDS";
                case GERMAN -> "WAS EIN STAND ENTHÄLT";
                case SPANISH -> "QUÉ GUARDA UN MOMENTO";
                case JAPANESE -> "時点に含まれるもの";
                case CHINESE -> "保存点包含的内容";
            };
        }

        public String ITS_HOME_THE_FILES_IT_MADE_ITS_NOTES_AND_ITS_CONVERSATIONS(String genie) {
            return switch (lang) {
                case ENGLISH ->
                    genie + "'s home: the files it made, its notes and its conversations, "
                    + "and the settings of its lamp. Going back brings all of these back together.";
                case GERMAN -> "Das Zuhause von " + genie + ": die Dateien, die er gemacht hat, seine Notizen und seine Gespräche, dazu die Einstellungen seiner Lampe. Beim Zurückgehen kommt all das gemeinsam zurück.";
                case SPANISH -> "El hogar de " + genie + ": los archivos que ha creado, sus notas y sus conversaciones, y los ajustes de su lámpara. Al volver atrás, todo esto vuelve a la vez.";
                case JAPANESE -> genie + " のホーム：作ったファイル、メモ、会話、そしてランプの設定。戻るときは、これらすべてが一緒に戻ります。";
                case CHINESE -> genie + " 的主目录：它创建的文件、它的笔记和对话，以及它神灯的设置。回退时，这些内容会一起恢复。";
            };
        }

        public String ITS_SCHEDULE_STAYS_AS_IT_IS() {
            return switch (lang) {
                case ENGLISH -> "Its schedule stays as it is, and so does everything on this computer outside the genie's lamp.";
                case GERMAN -> "Sein Zeitplan bleibt, wie er ist, und ebenso alles auf diesem Rechner außerhalb der Lampe des Dschinns.";
                case SPANISH -> "Su programación se queda como está, y también todo lo que hay en este equipo fuera de la lámpara del genio.";
                case JAPANESE -> "スケジュールは変わりません。ジーニーのランプの外にある、このコンピューター上のものもすべてそのままです。";
                case CHINESE -> "它的计划保持不变，这台电脑上精灵神灯之外的一切也都不变。";
            };
        }

        public String A_BRANCH_IS_A_LINE_OF_MOMENTS_WITH_A_HOME_OF_ITS_OWN(String genie) {
            return switch (lang) {
                case ENGLISH ->
                    "A branch is a line of moments with a home of its own, schedule and settings "
                    + "included, to try something without losing the rest. " + genie + " lives on one at a time.";
                case GERMAN -> "Ein Zweig ist eine Reihe von Ständen mit eigenem Zuhause, Zeitplan und Einstellungen inklusive, um etwas auszuprobieren, ohne den Rest zu verlieren. " + genie + " ist immer auf genau einem zu Hause.";
                case SPANISH -> "Una rama es una línea de momentos con su propio hogar, programación y ajustes incluidos, para probar algo sin perder lo demás. " + genie + " vive en una cada vez.";
                case JAPANESE -> "ブランチとは、スケジュールや設定も含めた独自のホームを持つ、時点の連なりです。ほかを失わずに何かを試せます。" + genie + " がいるのは、常にどれか 1 つのブランチです。";
                case CHINESE -> "分支是一串拥有独立主目录（包括计划和设置）的保存点，让你可以尝试新做法而不丢失其他内容。" + genie + " 每次只位于一个分支上。";
            };
        }

        public String SAVE_BY_HAND_BEFORE_SOMETHING_YOU_MIGHT_WANT_TO_UNDO() {
            return switch (lang) {
                case ENGLISH -> "Save by hand before something you might want to undo, such as a big change to a project.";
                case GERMAN -> "Sichere von Hand vor etwas, das du vielleicht rückgängig machen willst, etwa einer großen Änderung an einem Projekt.";
                case SPANISH -> "Guarda a mano antes de algo que quizá quieras deshacer, como un cambio grande en un proyecto.";
                case JAPANESE -> "元に戻したくなるかもしれない作業の前には、手動で保存しておきましょう。たとえば、プロジェクトに大きな変更を加える前などです。";
                case CHINESE -> "在做可能想撤销的事情之前，请手动保存，例如对项目做大改动之前。";
            };
        }

        public String NOTHING_IS_EVER_DELETED_ITS_HISTORY_MAY_GROW_WITHOUT_END(String genie) {
            return switch (lang) {
                case ENGLISH -> "Nothing is ever deleted: " + genie + "'s history may grow without end.";
                case GERMAN -> "Es wird nie etwas gelöscht: Der Verlauf von " + genie + " darf unbegrenzt wachsen.";
                case SPANISH -> "Nunca se elimina nada: el historial de " + genie + " puede crecer sin límite.";
                case JAPANESE -> "何も削除されません。" + genie + " の履歴は無制限に増えていきます。";
                case CHINESE -> "不会删除任何内容：" + genie + " 的历史可以无限增长。";
            };
        }

        public String ITS_HISTORY_MAY_GROW_BEYOND_ITS_FIRST_MOMENT(String genie, String size) {
            return switch (lang) {
                case ENGLISH ->
                    genie + "'s history may grow " + size + " beyond its first moment, on all branches "
                    + "together. Past that, each save deletes the moments saved longest ago; each branch keeps its newest.";
                case GERMAN -> "Der Verlauf von " + genie + " darf über seinen ersten Stand hinaus um " + size + " wachsen, alle Zweige zusammen. Darüber hinaus löscht jede Sicherung die ältesten Stände; jeder Zweig behält seinen neuesten.";
                case SPANISH -> "El historial de " + genie + " puede crecer " + size + " más allá de su primer momento, sumando todas las ramas. A partir de ahí, cada guardado elimina los momentos más antiguos; cada rama conserva el más reciente.";
                case JAPANESE -> genie + " の履歴は、最初の時点に加えて、全ブランチ合計で " + size + " まで増やせます。それを超えると、保存のたびに最も古い時点から削除されます。各ブランチの最新の時点は残ります。";
                case CHINESE -> genie + " 的历史在第一个保存点之外，所有分支合计最多可增长 " + size + "。超出后，每次保存都会删除最早的保存点；每个分支都会保留最新的保存点。";
            };
        }

        public String THE_LAST_TIME_OILLAMP_DELETED_OLD_MOMENTS(int count, String freed, String taken, String most) {
            return switch (lang) {
                case ENGLISH ->
                    "The last time, oillamp deleted " + (count == 1 ? "one old moment" : count + " old moments")
                    + " to stay within it, which freed " + freed + ". The history now takes "
                    + taken + " of the " + most + " it may, its first moment included.";
                case GERMAN -> "Beim letzten Mal hat oillamp " + (count == 1 ? "einen alten Stand" : count + " alte Stände") + " gelöscht, um im Rahmen zu bleiben, und so " + freed + " freigegeben. Der Verlauf belegt jetzt " + taken + " der erlaubten " + most + ", seinen ersten Stand eingerechnet.";
                case SPANISH -> "La última vez, oillamp eliminó " + (count == 1 ? "un momento antiguo" : count + " momentos antiguos") + " para no pasarse, lo que liberó " + freed + ". El historial ocupa ahora " + taken + " de los " + most + " que puede ocupar, incluido su primer momento.";
                case JAPANESE -> "前回、oillamp は上限内に収めるために古い時点を " + count + " 個削除し、" + freed + " を空けました。現在、履歴は最初の時点を含めて、上限 " + most + " のうち " + taken + " を使っています。";
                case CHINESE -> "上次 oillamp 为了不超出上限，删除了 " + count + " 个旧保存点，释放了 " + freed + "。现在历史已占用上限 " + most + " 中的 " + taken + "（含第一个保存点）。";
            };
        }

        public String TIDY_UP_NOW_ELLIPSIS() {
            return switch (lang) {
                case ENGLISH -> "Tidy up now…";
                case GERMAN -> "Jetzt aufräumen…";
                case SPANISH -> "Ordenar ahora…";
                case JAPANESE -> "今すぐ整理…";
                case CHINESE -> "立即清理…";
            };
        }

        public String DELETE_WHAT_DOES_NOT_FIT_NOW_RATHER_THAN_AT_THE_NEXT_SAVE() {
            return switch (lang) {
                case ENGLISH -> "Delete what does not fit now, rather than at the next save. You are told first what that is";
                case GERMAN -> "Jetzt löschen, was nicht mehr passt, statt erst bei der nächsten Sicherung. Vorher erfährst du, was das ist";
                case SPANISH -> "Eliminar ahora lo que no cabe, en vez de esperar al próximo guardado. Antes se te dice qué es";
                case JAPANESE -> "次の保存を待たずに、収まらない分を今すぐ削除します。その前に対象を表示します";
                case CHINESE -> "现在就删除超出的部分，而不是等到下次保存。删除前会先告诉你具体是哪些";
            };
        }

        public String NEVER_DELETE() {
            return switch (lang) {
                case ENGLISH -> "Never delete";
                case GERMAN -> "Nie löschen";
                case SPANISH -> "No eliminar nunca";
                case JAPANESE -> "削除しない";
                case CHINESE -> "从不删除";
            };
        }

        public String WHAT_SHOULD_THIS_MOMENT_BE_REMEMBERED_BY() {
            return switch (lang) {
                case ENGLISH -> "What should this moment be remembered by? You may leave it empty.";
                case GERMAN -> "Woran soll dieser Stand erinnern? Du kannst das Feld auch leer lassen.";
                case SPANISH -> "¿Cómo quieres recordar este momento? Puedes dejarlo vacío.";
                case JAPANESE -> "この時点にどんな説明を付けますか？空欄のままでも構いません。";
                case CHINESE -> "要给这个保存点写个什么说明？也可以留空。";
            };
        }

        public String SAVE_AS_IT_IS_NOW(String genie) {
            return switch (lang) {
                case ENGLISH -> "Save " + genie + " as it is now";
                case GERMAN -> genie + " im jetzigen Zustand sichern";
                case SPANISH -> "Guardar a " + genie + " tal como está ahora";
                case JAPANESE -> genie + " を今の状態で保存";
                case CHINESE -> "按当前状态保存 " + genie;
            };
        }

        public String WHAT_SHOULD_THE_NEW_BRANCH_BE_CALLED(String genie, String home) {
            return switch (lang) {
                case ENGLISH ->
                    "What should the new branch be called? Lowercase letters, digits, -, _ and ., such as rust-rewrite.\n"
                    + genie + " stays on " + home + "; you can move it later, while it sleeps.";
                case GERMAN -> "Wie soll der neue Zweig heißen? Kleinbuchstaben, Ziffern, -, _ und ., etwa rust-rewrite.\n" + genie + " bleibt auf " + home + "; du kannst ihn später umziehen lassen, während er schläft.";
                case SPANISH -> "¿Cómo se llamará la rama nueva? Letras minúsculas, cifras, -, _ y ., como rust-rewrite.\n" + genie + " se queda en " + home + "; puedes moverlo más tarde, mientras duerme.";
                case JAPANESE -> "新しいブランチの名前は？小文字の英字、数字、-、_、. が使えます。例：rust-rewrite\n" + genie + " は " + home + " にいたままです。眠っている間なら、あとで移せます。";
                case CHINESE -> "新分支叫什么名字？可使用小写字母、数字、-、_ 和 .，例如 rust-rewrite。\n" + genie + " 仍留在 " + home + " 上；之后可以在它休眠时再移动它。";
            };
        }

        public String START_A_BRANCH_FROM_THE_NEWEST_MOMENT() {
            return switch (lang) {
                case ENGLISH -> "Start a branch from the newest moment";
                case GERMAN -> "Einen Zweig vom neuesten Stand aus beginnen";
                case SPANISH -> "Empezar una rama desde el momento más reciente";
                case JAPANESE -> "最新の時点からブランチを作成";
                case CHINESE -> "从最新的保存点创建分支";
            };
        }

        public String START_A_BRANCH_FROM_THIS_MOMENT() {
            return switch (lang) {
                case ENGLISH -> "Start a branch from this moment";
                case GERMAN -> "Einen Zweig von diesem Stand aus beginnen";
                case SPANISH -> "Empezar una rama desde este momento";
                case JAPANESE -> "この時点からブランチを作成";
                case CHINESE -> "从此保存点创建分支";
            };
        }

        public String BRING_IT_BACK_TO_HOW_IT_WAS(String genie, String when) {
            return switch (lang) {
                case ENGLISH ->
                    "Bring " + genie + " back to how it was " + when + "?\n"
                    + "Its files and conversations become what they were then. How it is now is kept, so you can return.";
                case GERMAN -> genie + " auf den Stand zurückbringen, wie er " + when + " war?\nSeine Dateien und Gespräche werden wieder so, wie sie damals waren. Der jetzige Stand bleibt erhalten, du kannst also zurückkehren.";
                case SPANISH -> "¿Devolver a " + genie + " a como estaba " + when + "?\nSus archivos y conversaciones vuelven a ser como eran entonces. Cómo está ahora se conserva, así que puedes regresar.";
                case JAPANESE -> genie + " を" + when + "の状態に戻しますか？\nファイルと会話は当時のものになります。今の状態は残るので、また戻ってこられます。";
                case CHINESE -> "要将 " + genie + " 恢复到" + when + "时的状态吗？\n它的文件和对话将变回当时的样子。当前状态会保留，你可以随时回来。";
            };
        }

        public String SLEEPS_WHILE_IT_GOES_BACK_AND_WAKES_AGAIN_AFTER(String genie) {
            return switch (lang) {
                case ENGLISH -> genie + " sleeps while it goes back, and wakes again after.";
                case GERMAN -> genie + " schläft, während er zurückgeht, und wacht danach wieder auf.";
                case SPANISH -> genie + " duerme mientras vuelve atrás, y después despierta otra vez.";
                case JAPANESE -> genie + " は戻る間は眠り、終わったらまた起きます。";
                case CHINESE -> genie + " 在回退期间会休眠，完成后再醒来。";
            };
        }

        public String GO_BACK_IN_TIME() {
            return switch (lang) {
                case ENGLISH -> "Go back in time";
                case GERMAN -> "Zurück in der Zeit";
                case SPANISH -> "Volver en el tiempo";
                case JAPANESE -> "時間をさかのぼる";
                case CHINESE -> "回到过去";
            };
        }

        public String GO_BACK() {
            return switch (lang) {
                case ENGLISH -> "Go back";
                case GERMAN -> "Zurückgehen";
                case SPANISH -> "Volver atrás";
                case JAPANESE -> "戻る";
                case CHINESE -> "回退";
            };
        }

        public String STAY() {
            return switch (lang) {
                case ENGLISH -> "Stay";
                case GERMAN -> "Bleiben";
                case SPANISH -> "Quedarse";
                case JAPANESE -> "このままにする";
                case CHINESE -> "保持现状";
            };
        }

        public String MOVE_IT_TO_ANOTHER_BRANCH(String genie, String to, String from) {
            return switch (lang) {
                case ENGLISH ->
                    "Move " + genie + " to " + to + "?\n"
                    + "Its files and conversations become those of the newest moment on " + to
                    + ", and every save goes to " + to + " from now on.\n"
                    + "What changed since the newest moment on " + from + " is saved there first, so nothing is lost.";
                case GERMAN -> genie + " nach " + to + " umziehen lassen?\nSeine Dateien und Gespräche werden die des neuesten Stands auf " + to + ", und jede Sicherung landet ab jetzt auf " + to + ".\nWas sich seit dem neuesten Stand auf " + from + " geändert hat, wird vorher dort gesichert, es geht also nichts verloren.";
                case SPANISH -> "¿Mover a " + genie + " a " + to + "?\nSus archivos y conversaciones pasan a ser los del momento más reciente de " + to + ", y desde ahora cada guardado va a " + to + ".\nLo que cambió desde el momento más reciente de " + from + " se guarda antes allí, así que no se pierde nada.";
                case JAPANESE -> genie + " を " + to + " に移しますか？\nファイルと会話は " + to + " の最新の時点のものになり、今後の保存はすべて " + to + " に入ります。\n" + from + " の最新の時点以降に変わった内容は、先にそちらに保存されるので、何も失われません。";
                case CHINESE -> "要将 " + genie + " 移到 " + to + " 吗？\n它的文件和对话将变成 " + to + " 上最新保存点的内容，此后每次保存都会存到 " + to + "。\n自 " + from + " 上最新保存点以来的更改会先保存在那里，因此不会丢失任何内容。";
            };
        }

        public String MOVE_TO_ANOTHER_BRANCH() {
            return switch (lang) {
                case ENGLISH -> "Move to another branch";
                case GERMAN -> "Auf einen anderen Zweig umziehen";
                case SPANISH -> "Mover a otra rama";
                case JAPANESE -> "別のブランチに移す";
                case CHINESE -> "移到其他分支";
            };
        }

        public String MOVE() {
            return switch (lang) {
                case ENGLISH -> "Move";
                case GERMAN -> "Umziehen";
                case SPANISH -> "Mover";
                case JAPANESE -> "移す";
                case CHINESE -> "移动";
            };
        }

        public String WOKE_UP() {
            return switch (lang) {
                case ENGLISH -> "Woke up";
                case GERMAN -> "Aufgewacht";
                case SPANISH -> "Se despertó";
                case JAPANESE -> "起きた";
                case CHINESE -> "已醒来";
            };
        }

        public String WENT_TO_SLEEP() {
            return switch (lang) {
                case ENGLISH -> "Went to sleep";
                case GERMAN -> "Eingeschlafen";
                case SPANISH -> "Se durmió";
                case JAPANESE -> "眠った";
                case CHINESE -> "已休眠";
            };
        }

        public String SAVED_BY_HAND() {
            return switch (lang) {
                case ENGLISH -> "Saved by hand";
                case GERMAN -> "Von Hand gesichert";
                case SPANISH -> "Guardado a mano";
                case JAPANESE -> "手動で保存";
                case CHINESE -> "手动保存";
            };
        }

        public String BEFORE_IT_WENT_TO_WORK() {
            return switch (lang) {
                case ENGLISH -> "Before it went to work";
                case GERMAN -> "Bevor er sich an die Arbeit machte";
                case SPANISH -> "Antes de ponerse a trabajar";
                case JAPANESE -> "作業を始める前";
                case CHINESE -> "开始工作前";
            };
        }

        public String DID_THE_JOB(String job) {
            return switch (lang) {
                case ENGLISH -> "Did the job: " + job;
                case GERMAN -> "Hat den Auftrag erledigt: " + job;
                case SPANISH -> "Hizo la tarea: " + job;
                case JAPANESE -> "ジョブを実行：" + job;
                case CHINESE -> "执行了任务：" + job;
            };
        }

        public String DID_A_SCHEDULED_JOB() {
            return switch (lang) {
                case ENGLISH -> "Did a scheduled job";
                case GERMAN -> "Hat einen geplanten Auftrag erledigt";
                case SPANISH -> "Hizo una tarea programada";
                case JAPANESE -> "スケジュールされたジョブを実行";
                case CHINESE -> "执行了计划任务";
            };
        }

        public String ANSWERED(String conversation) {
            return switch (lang) {
                case ENGLISH -> "Answered: " + conversation;
                case GERMAN -> "Hat geantwortet: " + conversation;
                case SPANISH -> "Respondió: " + conversation;
                case JAPANESE -> "回答：" + conversation;
                case CHINESE -> "已回答：" + conversation;
            };
        }

        public String ANSWERED_A_QUESTION() {
            return switch (lang) {
                case ENGLISH -> "Answered a question";
                case GERMAN -> "Hat eine Frage beantwortet";
                case SPANISH -> "Respondió una pregunta";
                case JAPANESE -> "質問に回答";
                case CHINESE -> "回答了一个问题";
            };
        }

        public String TALKED(String conversation) {
            return switch (lang) {
                case ENGLISH -> "Talked: " + conversation;
                case GERMAN -> "Hat sich unterhalten: " + conversation;
                case SPANISH -> "Conversó: " + conversation;
                case JAPANESE -> "会話：" + conversation;
                case CHINESE -> "交谈：" + conversation;
            };
        }

        public String TALKED_WITH_YOU() {
            return switch (lang) {
                case ENGLISH -> "Talked with you";
                case GERMAN -> "Hat mit dir geredet";
                case SPANISH -> "Habló contigo";
                case JAPANESE -> "あなたと会話";
                case CHINESE -> "与你交谈过";
            };
        }

        public String BEFORE_GOING_BACK() {
            return switch (lang) {
                case ENGLISH -> "Before going back";
                case GERMAN -> "Vor dem Zurückgehen";
                case SPANISH -> "Antes de volver atrás";
                case JAPANESE -> "戻る前";
                case CHINESE -> "回退前";
            };
        }

        public String UNDID_GOING_BACK_TO_HOW_IT_WAS(String when) {
            return switch (lang) {
                case ENGLISH -> "Undid going back to how it was " + when;
                case GERMAN -> "Rückkehr zum Stand, wie er " + when + " war, rückgängig gemacht";
                case SPANISH -> "Deshizo la vuelta a como estaba " + when;
                case JAPANESE -> when + "の状態に戻したのを取り消し";
                case CHINESE -> "撤销了回到" + when + "时状态的操作";
            };
        }

        public String WENT_BACK_TO_HOW_IT_WAS(String when) {
            return switch (lang) {
                case ENGLISH -> "Went back to how it was " + when;
                case GERMAN -> "Zum Stand zurückgekehrt, wie er " + when + " war";
                case SPANISH -> "Volvió a como estaba " + when;
                case JAPANESE -> when + "の状態に戻った";
                case CHINESE -> "回到了" + when + "时的状态";
            };
        }

        public String WENT_BACK_TO_AN_EARLIER_MOMENT() {
            return switch (lang) {
                case ENGLISH -> "Went back to an earlier moment";
                case GERMAN -> "Zu einem früheren Stand zurückgekehrt";
                case SPANISH -> "Volvió a un momento anterior";
                case JAPANESE -> "以前の時点に戻った";
                case CHINESE -> "回到了更早的保存点";
            };
        }

        public String BEFORE_IT_MOVED_TO_ANOTHER_BRANCH() {
            return switch (lang) {
                case ENGLISH -> "Before it moved to another branch";
                case GERMAN -> "Bevor er auf einen anderen Zweig umzog";
                case SPANISH -> "Antes de mudarse a otra rama";
                case JAPANESE -> "別のブランチに移る前";
                case CHINESE -> "移到其他分支前";
            };
        }

        public String SAVED_AS_ITS_SANDBOX_STARTED() {
            return switch (lang) {
                case ENGLISH -> "Saved as its sandbox started";
                case GERMAN -> "Gesichert beim Start seiner Sandbox";
                case SPANISH -> "Guardado al iniciarse su sandbox";
                case JAPANESE -> "サンドボックスの起動時に保存";
                case CHINESE -> "沙箱启动时保存";
            };
        }

        public String SAVED_AFTER_ITS_SANDBOX_STOPPED() {
            return switch (lang) {
                case ENGLISH -> "Saved after its sandbox stopped";
                case GERMAN -> "Gesichert nach dem Ende seiner Sandbox";
                case SPANISH -> "Guardado después de que se detuviera su sandbox";
                case JAPANESE -> "サンドボックスの停止後に保存";
                case CHINESE -> "沙箱停止后保存";
            };
        }

        public String SAVED_AS_IT_BEGAN_ON_A_QUESTION_OR_A_JOB() {
            return switch (lang) {
                case ENGLISH -> "Saved as it began on a question or a job";
                case GERMAN -> "Gesichert, als er sich an eine Frage oder einen Auftrag machte";
                case SPANISH -> "Guardado al empezar con una pregunta o una tarea";
                case JAPANESE -> "質問やジョブに取りかかったときに保存";
                case CHINESE -> "开始处理问题或任务时保存";
            };
        }

        public String SAVED_ONCE_THE_CHAT_HAD_GONE_QUIET() {
            return switch (lang) {
                case ENGLISH -> "Saved once the chat had gone quiet";
                case GERMAN -> "Gesichert, als es im Chat ruhig geworden war";
                case SPANISH -> "Guardado cuando el chat se quedó en calma";
                case JAPANESE -> "チャットが落ち着いたときに保存";
                case CHINESE -> "聊天安静下来后保存";
            };
        }

        public String STOPPED_AND_SAID(String said) {
            return switch (lang) {
                case ENGLISH -> "Stopped. " + said;
                case GERMAN -> "Angehalten. " + said;
                case SPANISH -> "Detenida. " + said;
                case JAPANESE -> "停止。" + said;
                case CHINESE -> "已停止。" + said;
            };
        }

        public String FAILED_AND_SAID(String said) {
            return switch (lang) {
                case ENGLISH -> "Failed. " + said;
                case GERMAN -> "Fehlgeschlagen. " + said;
                case SPANISH -> "Falló. " + said;
                case JAPANESE -> "失敗。" + said;
                case CHINESE -> "失败。" + said;
            };
        }

        public String RAN_OUT_OF_TIME_AND_SAID(String said) {
            return switch (lang) {
                case ENGLISH -> "Ran out of time. " + said;
                case GERMAN -> "Zeit abgelaufen. " + said;
                case SPANISH -> "Se quedó sin tiempo. " + said;
                case JAPANESE -> "時間切れ。" + said;
                case CHINESE -> "超时。" + said;
            };
        }

        public String KEPT_SO_THAT_GOING_BACK_CAN_BE_UNDONE() {
            return switch (lang) {
                case ENGLISH -> "Kept so that going back can be undone";
                case GERMAN -> "Behalten, damit sich das Zurückgehen rückgängig machen lässt";
                case SPANISH -> "Conservado para poder deshacer la vuelta atrás";
                case JAPANESE -> "戻る操作を取り消せるように保存";
                case CHINESE -> "已保留，以便撤销回退";
            };
        }

        public String KEPT_AS_IT_WAS_WHEN_IT_MOVED_TO_ANOTHER_BRANCH() {
            return switch (lang) {
                case ENGLISH -> "Kept as it was when it moved to another branch";
                case GERMAN -> "So behalten, wie er beim Umzug auf einen anderen Zweig war";
                case SPANISH -> "Conservado tal como estaba al mudarse a otra rama";
                case JAPANESE -> "別のブランチに移ったときの状態で保存";
                case CHINESE -> "按移到其他分支时的状态保留";
            };
        }

        public String WAKES() {
            return switch (lang) {
                case ENGLISH -> "wakes";
                case GERMAN -> "aufwacht";
                case SPANISH -> "despierta";
                case JAPANESE -> "起きる";
                case CHINESE -> "醒来";
            };
        }

        public String ANSWERS() {
            return switch (lang) {
                case ENGLISH -> "answers";
                case GERMAN -> "antwortet";
                case SPANISH -> "responde";
                case JAPANESE -> "答える";
                case CHINESE -> "回答";
            };
        }

        public String BEGINS_TO_ANSWER() {
            return switch (lang) {
                case ENGLISH -> "begins to answer";
                case GERMAN -> "zu antworten beginnt";
                case SPANISH -> "empieza a responder";
                case JAPANESE -> "答え始める";
                case CHINESE -> "开始回答";
            };
        }

        public String SLEEPS() {
            return switch (lang) {
                case ENGLISH -> "sleeps";
                case GERMAN -> "einschläft";
                case SPANISH -> "se duerme";
                case JAPANESE -> "眠る";
                case CHINESE -> "休眠";
            };
        }

        public String ONE_OR_ANOTHER(List<String> names) {
            return switch (lang) {
                case ENGLISH -> eitherInEnglish(names);
                case GERMAN -> eitherInGerman(names);
                case SPANISH -> eitherInSpanish(names);
                case JAPANESE -> eitherInJapanese(names);
                case CHINESE -> eitherInChinese(names);
            };
        }

        public String A_BRANCH_NEEDS_A_NAME() {
            return switch (lang) {
                case ENGLISH -> "A branch needs a name, such as rust-rewrite.";
                case GERMAN -> "Ein Zweig braucht einen Namen, etwa rust-rewrite.";
                case SPANISH -> "Una rama necesita un nombre, como rust-rewrite.";
                case JAPANESE -> "ブランチには名前が必要です。例：rust-rewrite";
                case CHINESE -> "分支需要一个名称，例如 rust-rewrite。";
            };
        }

        public String A_BRANCHS_NAME_IS_AT_MOST_SO_MANY_CHARACTERS_LONG(int most) {
            return switch (lang) {
                case ENGLISH -> "A branch's name is at most " + most + " characters long.";
                case GERMAN -> "Der Name eines Zweigs ist höchstens " + most + " Zeichen lang.";
                case SPANISH -> "El nombre de una rama tiene como mucho " + most + " caracteres.";
                case JAPANESE -> "ブランチ名は " + most + " 文字以内です。";
                case CHINESE -> "分支名称最多 " + most + " 个字符。";
            };
        }

        public String A_BRANCHS_NAME_IS_MADE_OF_LOWERCASE_LETTERS_DIGITS_AND_SIGNS() {
            return switch (lang) {
                case ENGLISH -> "A branch's name is made of lowercase letters, digits, -, _ and ., and begins with a letter or a digit, such as rust-rewrite.";
                case GERMAN -> "Der Name eines Zweigs besteht aus Kleinbuchstaben, Ziffern, -, _ und . und beginnt mit einem Buchstaben oder einer Ziffer, etwa rust-rewrite.";
                case SPANISH -> "El nombre de una rama se compone de letras minúsculas, cifras, -, _ y ., y empieza por una letra o una cifra, como rust-rewrite.";
                case JAPANESE -> "ブランチ名には小文字の英字、数字、-、_、. が使え、英字か数字で始めます。例：rust-rewrite";
                case CHINESE -> "分支名称由小写字母、数字、-、_ 和 . 组成，并以字母或数字开头，例如 rust-rewrite。";
            };
        }

        public String A_BRANCHS_NAME_CANNOT_HOLD_TWO_DOTS_OR_END_IN_A_DOT_OR_LOCK() {
            return switch (lang) {
                case ENGLISH -> "A branch's name cannot hold .., or end in . or .lock.";
                case GERMAN -> "Der Name eines Zweigs darf kein .. enthalten und nicht auf . oder .lock enden.";
                case SPANISH -> "El nombre de una rama no puede contener .., ni terminar en . o .lock.";
                case JAPANESE -> "ブランチ名には .. を含められず、. や .lock で終えることもできません。";
                case CHINESE -> "分支名称不能包含 ..，也不能以 . 或 .lock 结尾。";
            };
        }

        public String THERE_IS_A_BRANCH_CALLED_IT_ALREADY(String branch) {
            return switch (lang) {
                case ENGLISH -> "There is a branch called " + branch + " already.";
                case GERMAN -> "Es gibt schon einen Zweig namens " + branch + ".";
                case SPANISH -> "Ya hay una rama llamada " + branch + ".";
                case JAPANESE -> branch + " という名前のブランチはすでにあります。";
                case CHINESE -> "已经有名为 " + branch + " 的分支了。";
            };
        }
    }

    /// Days and spans of time, as the pages say them; [DateWordingUtil] says which of them fits.
    public record Dates(Lang lang) {
        public String TODAY() {
            return switch (lang) {
                case ENGLISH -> "Today";
                case GERMAN -> "Heute";
                case SPANISH -> "Hoy";
                case JAPANESE -> "今日";
                case CHINESE -> "今天";
            };
        }

        public String TOMORROW() {
            return switch (lang) {
                case ENGLISH -> "Tomorrow";
                case GERMAN -> "Morgen";
                case SPANISH -> "Mañana";
                case JAPANESE -> "明日";
                case CHINESE -> "明天";
            };
        }

        public String YESTERDAY() {
            return switch (lang) {
                case ENGLISH -> "Yesterday";
                case GERMAN -> "Gestern";
                case SPANISH -> "Ayer";
                case JAPANESE -> "昨日";
                case CHINESE -> "昨天";
            };
        }

        public String DAY(LocalDate day) {
            return switch (lang) {
                case ENGLISH -> DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH).format(day);
                case GERMAN -> DateTimeFormatter.ofPattern("EEEE, d. MMMM", Locale.GERMAN).format(day);
                case SPANISH -> DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", Locale.of("es")).format(day);
                case JAPANESE -> DateTimeFormatter.ofPattern("M月d日（E）", Locale.JAPANESE).format(day);
                case CHINESE -> DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINESE).format(day);
            };
        }

        public String TODAY_IN_A_SENTENCE() {
            return switch (lang) {
                case ENGLISH -> "today";
                case GERMAN -> "heute";
                case SPANISH -> "hoy";
                case JAPANESE -> "今日";
                case CHINESE -> "今天";
            };
        }

        public String TOMORROW_IN_A_SENTENCE() {
            return switch (lang) {
                case ENGLISH -> "tomorrow";
                case GERMAN -> "morgen";
                case SPANISH -> "mañana";
                case JAPANESE -> "明日";
                case CHINESE -> "明天";
            };
        }

        public String YESTERDAY_IN_A_SENTENCE() {
            return switch (lang) {
                case ENGLISH -> "yesterday";
                case GERMAN -> "gestern";
                case SPANISH -> "ayer";
                case JAPANESE -> "昨日";
                case CHINESE -> "昨天";
            };
        }

        public String ON_DAY(String date) {
            return switch (lang) {
                case ENGLISH -> "on " + date;
                case GERMAN -> "am " + date;
                case SPANISH -> "el " + date;
                case JAPANESE -> date;
                case CHINESE -> date;
            };
        }

        public String SHORT_DAY(LocalDate day) {
            return switch (lang) {
                case ENGLISH -> DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH).format(day);
                case GERMAN -> DateTimeFormatter.ofPattern("EEE, d. MMM", Locale.GERMAN).format(day);
                case SPANISH -> DateTimeFormatter.ofPattern("EEE d MMM", Locale.of("es")).format(day);
                case JAPANESE -> DateTimeFormatter.ofPattern("M/d（E）", Locale.JAPANESE).format(day);
                case CHINESE -> DateTimeFormatter.ofPattern("M月d日 E", Locale.CHINESE).format(day);
            };
        }

        public String NOW() {
            return switch (lang) {
                case ENGLISH -> "now";
                case GERMAN -> "jetzt";
                case SPANISH -> "ahora";
                case JAPANESE -> "今";
                case CHINESE -> "现在";
            };
        }

        public String IN_MINUTES(int count) {
            return switch (lang) {
                case ENGLISH -> "in " + count + (count == 1 ? " minute" : " minutes");
                case GERMAN -> "in " + count + (count == 1 ? " Minute" : " Minuten");
                case SPANISH -> "dentro de " + count + (count == 1 ? " minuto" : " minutos");
                case JAPANESE -> count + " 分後";
                case CHINESE -> count + " 分钟后";
            };
        }

        public String IN_HOURS(int count) {
            return switch (lang) {
                case ENGLISH -> "in " + count + (count == 1 ? " hour" : " hours");
                case GERMAN -> "in " + count + (count == 1 ? " Stunde" : " Stunden");
                case SPANISH -> "dentro de " + count + (count == 1 ? " hora" : " horas");
                case JAPANESE -> count + " 時間後";
                case CHINESE -> count + " 小时后";
            };
        }

        public String IN_DAYS(int count) {
            return switch (lang) {
                case ENGLISH -> "in " + count + (count == 1 ? " day" : " days");
                case GERMAN -> "in " + count + (count == 1 ? " Tag" : " Tagen");
                case SPANISH -> "dentro de " + count + (count == 1 ? " día" : " días");
                case JAPANESE -> count + " 日後";
                case CHINESE -> count + " 天后";
            };
        }

        public String JUST_NOW() {
            return switch (lang) {
                case ENGLISH -> "just now";
                case GERMAN -> "gerade eben";
                case SPANISH -> "hace un momento";
                case JAPANESE -> "たった今";
                case CHINESE -> "刚刚";
            };
        }

        public String MINUTES_AGO(int count) {
            return switch (lang) {
                case ENGLISH -> count + (count == 1 ? " minute ago" : " minutes ago");
                case GERMAN -> "vor " + count + (count == 1 ? " Minute" : " Minuten");
                case SPANISH -> "hace " + count + (count == 1 ? " minuto" : " minutos");
                case JAPANESE -> count + " 分前";
                case CHINESE -> count + " 分钟前";
            };
        }

        public String HOURS_AGO(int count) {
            return switch (lang) {
                case ENGLISH -> count + (count == 1 ? " hour ago" : " hours ago");
                case GERMAN -> "vor " + count + (count == 1 ? " Stunde" : " Stunden");
                case SPANISH -> "hace " + count + (count == 1 ? " hora" : " horas");
                case JAPANESE -> count + " 時間前";
                case CHINESE -> count + " 小时前";
            };
        }

        public String DAYS_AGO(int count) {
            return switch (lang) {
                case ENGLISH -> count + (count == 1 ? " day ago" : " days ago");
                case GERMAN -> "vor " + count + (count == 1 ? " Tag" : " Tagen");
                case SPANISH -> "hace " + count + (count == 1 ? " día" : " días");
                case JAPANESE -> count + " 日前";
                case CHINESE -> count + " 天前";
            };
        }

        public String AT(String date, String time) {
            return switch (lang) {
                case ENGLISH -> date + " at " + time;
                case GERMAN -> date + " um " + time;
                case SPANISH -> date + " a " + hourInSpanish(time);
                case JAPANESE -> date + " " + time;
                case CHINESE -> date + " " + time;
            };
        }
    }

    /// The words of the parts of the window that several pages share.
    public record Parts(Lang lang) {
        public String NO() {
            return switch (lang) {
                case ENGLISH -> "No";
                case GERMAN -> "Nein";
                case SPANISH -> "No";
                case JAPANESE -> "いいえ";
                case CHINESE -> "否";
            };
        }

        public String YES() {
            return switch (lang) {
                case ENGLISH -> "Yes";
                case GERMAN -> "Ja";
                case SPANISH -> "Sí";
                case JAPANESE -> "はい";
                case CHINESE -> "是";
            };
        }
    }
}
