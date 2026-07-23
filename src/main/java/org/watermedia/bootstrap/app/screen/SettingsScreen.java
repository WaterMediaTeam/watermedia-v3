package org.watermedia.bootstrap.app.screen;

import me.srrapero720.waterconfig.ConfigGroup;
import me.srrapero720.waterconfig.ConfigSpec;
import me.srrapero720.waterconfig.WaterConfig;
import me.srrapero720.waterconfig.api.Control;
import me.srrapero720.waterconfig.api.IConfigField;
import org.watermedia.WaterMedia;
import org.watermedia.bootstrap.app.AppConfig;
import org.watermedia.bootstrap.app.AppContext;
import org.watermedia.bootstrap.app.element.Box;
import org.watermedia.bootstrap.app.element.Button;
import org.watermedia.bootstrap.app.element.Canvas;
import org.watermedia.bootstrap.app.element.CheckBox;
import org.watermedia.bootstrap.app.element.Dropdown;
import org.watermedia.bootstrap.app.element.Element;
import org.watermedia.bootstrap.app.element.Group;
import org.watermedia.bootstrap.app.element.ListView;
import org.watermedia.bootstrap.app.element.Parent;
import org.watermedia.bootstrap.app.element.ParentScroll;
import org.watermedia.bootstrap.app.element.SeekBar;
import org.watermedia.bootstrap.app.element.SegmentedControl;
import org.watermedia.bootstrap.app.element.Spinner;
import org.watermedia.bootstrap.app.element.StatusSquare;
import org.watermedia.bootstrap.app.element.Switch;
import org.watermedia.bootstrap.app.element.Text;
import org.watermedia.bootstrap.app.element.TextField;
import org.watermedia.bootstrap.app.element.Theme;
import org.watermedia.bootstrap.app.UiScale;
import org.watermedia.bootstrap.app.WaterMediaApp;
import org.watermedia.bootstrap.app.PlayerTarget;
import org.watermedia.bootstrap.app.render.RenderSystem;
import org.watermedia.bootstrap.app.render.RenderMode;
import org.watermedia.bootstrap.app.ui.AppTheme;
import org.watermedia.bootstrap.app.ui.Gravity;
import org.watermedia.bootstrap.app.ui.Spacing;
import org.watermedia.bootstrap.app.ui.TextRenderer;
import org.watermedia.tools.ThreadTool;

import java.awt.Color;
import java.awt.Font;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Settings screen reflecting WaterConfig specs as a fully retained view tree: a spec tab strip over a
 * sections column and a {@link ListView} of setting rows backed by real widgets (switch, checkbox,
 * segmented control, spinner, seek bar, text field, dropdown). One reflective path builds every tab from
 * its {@link ConfigSpec}; each tab carries a status pip fed by that spec's own autosave machine. The
 * tab set, the reverse-spec'd mod files and the injected runtime rows are described at their code below.
 */
public final class SettingsScreen extends Screen {

    private static final int SIDEBAR_W = 220;
    private static final int ROW_H = 56;
    private static final int CONTROL_W = 320;
    private static final int CONTROL_H = 26;
    // TALL ENOUGH FOR THE TITLE+SUBTITLE BLOCK (~35px) PLUS REAL BREATHING ROOM ABOVE AND BELOW —
    // AT 48 THE TITLE SAT FLUSH AGAINST THE FOLDER'S TOP EDGE
    private static final int TAB_H = 64;
    private static final int SECTION_H = 36;

    // EXTERNAL MOD CONFIG FILES THE SCREEN TRIES TO REVERSE-ENGINEER FROM THE CONFIG DIR INTO EDITABLE
    // TABS — SKIPPED SILENTLY WHEN THE FILE IS ABSENT OR CANNOT BE PARSED (SEE buildReverseSpec)
    private static final String[] REVERSE_FILES = {"chloride-client.json", "waterframes-client.toml"};

    // THE RENDER-MODE SETTING KEY, HOISTED SO THE SEGMENTED-CONTROL EXEMPTION AND THE ROW CREATION CANNOT DRIFT APART
    private static final String RENDER_KEY = "app.engines.render";

    // ConfigSpec.save() IS PACKAGE-PRIVATE IN WATERCONFIG, SO IT IS REFLECTED ONCE HERE INSTEAD OF PER
    // SAVE; A null (METHOD RENAMED/HIDDEN UPSTREAM) SURFACES LATER AS A FAILED SAVE STATE
    private static final Method CONFIG_SAVE;
    static {
        Method save = null;
        try {
            save = ConfigSpec.class.getDeclaredMethod("save");
            save.setAccessible(true);
        } catch (final ReflectiveOperationException | RuntimeException e) {
            save = null;
        }
        CONFIG_SAVE = save;
    }

    // PER-TAB ACCENTS, CYCLED BY TAB INDEX — TAB 0 (SHELL) CYAN, TAB 1 (INSTANCE) AMBER, THEN THE REST
    private static final Color[] TAB_ACCENTS = {AppTheme.CYAN, AppTheme.AMBER, AppTheme.GREEN, AppTheme.NEON_LIGHT, AppTheme.NEON};

    private final Consumer<HomeScreen.Action> navigator;
    private final List<SettingSpec> specs = new ArrayList<>();
    // EVERY TEXT FIELD OF THE CURRENT ROWS — FOCUS IS RECONCILED AFTER EACH CLICK (ONE EDIT AT MOST)
    private final List<TextField> textFields = new ArrayList<>();

    // TREE SLOTS — BUILT ONCE IN build(), REFILLED ON SPEC/SECTION SWITCHES
    private Header header;
    private TabScroll tabs;
    private Button reset;
    private Parent sectionColumn;
    private Text paneTitle;
    private Text paneDetail;
    private ListView<Setting> list;

    private int activeSpecIndex;
    private int activeSectionIndex;

    // ACTIVE TEXT EDIT — ENTER COMMITS THROUGH Setting.commit, ESC CANCELS (VALUE RE-SYNCS FROM THE SETTING)
    private TextField editField;
    private Setting editSetting;

    // ENUM DROPDOWN POPUPS MOUNT INTO this.overlay(); popupKey REMEMBERS A NAV/DISMISS KEY A POPUP CONSUMED
    // ON PRESS SO THE MATCHING RELEASE IS SWALLOWED INSTEAD OF FIRING A SCREEN SHORTCUT (BACK/ACTIVATE)
    private int popupKey = -1;

    // SEEKBAR GESTURES COALESCE INTO ONE SAVE, FLUSHED WHEN THE POINTER RELEASES (SEE onUpdate).
    // dragSaveSpec PINS THE SPEC BEING MUTATED AT ARM TIME SO A MID-GESTURE SPEC SWITCH (TAB/PGUP/PGDN
    // STILL DISPATCH DURING A SEEKBAR CAPTURE) FLUSHES THE MUTATED SPEC, NOT WHATEVER IS ACTIVE AT RELEASE
    private boolean dragSave;
    private SettingSpec dragSaveSpec;

    // ONE AUTOSAVE MACHINE PER SPEC (KEYED BY THE SPEC'S STABLE IDENTITY) FEEDING THAT SPEC'S TAB PIP.
    // THE MACHINES OUTLIVE rebuildSpecs SO AN IN-FLIGHT SAVE KEEPS ITS COALESCING GATE ACROSS SCREEN
    // RE-ENTRIES AND ACROSS THE VARIABLE SPEC SET (WATERCONFIG-EXPOSED + REVERSE-SPEC'D FILES).
    private final Map<String, SaveMachine> saves = new HashMap<>();

    // REVERSE-SPEC PARSE CACHE KEYED BY FILE — REUSED WHILE THE FILE'S mtime IS UNCHANGED SO A SCREEN
    // RE-ENTRY DOES NOT RE-READ AND RE-PARSE UNCHANGED EXTERNAL MOD CONFIGS ON THE RENDER THREAD
    private final Map<String, ReverseSpec> reverseCache = new HashMap<>();

    // HOVERED ROW DESCRIPTION, REPUBLISHED EVERY DRAW PASS — FEEDS THE CURSOR TOOLTIP (SEE onDraw)
    private String hoverNote;

    public SettingsScreen(final TextRenderer text, final AppContext ctx,
                          final Consumer<HomeScreen.Action> navigator) {
        super(text, ctx);
        this.navigator = navigator;
    }

    @Override
    protected Element<?> build() {
        // HEADER — VERSION TAG ONLY; THE RESET ACTION LIVES IN THE SPEC TAB STRIP BELOW
        this.header = new Header().name("Settings").right("v" + WaterMedia.VERSION);

        // SPEC TAB STRIP — HORIZONTALLY SCROLLABLE FOLDER TABS UNDER THE HEADER WITH RESET PINNED RIGHT:
        // [ APP ][ INSTANCE ][ … more specs, scroll → ] ................. [ RESET TO DEFAULT ]
        this.reset = new Button("RESET TO DEFAULT").icon("reset")
                .accent(AppTheme.AMBER).textColor(AppTheme.TEXT_SOFT)
                .size(Math.max(190, Button.width(this.text, "RESET TO DEFAULT", "", "reset", 12) + 24), Theme.BUTTON)
                .gravity(Gravity.CENTER)
                .onClick(v -> {
                    this.resetActiveSection();
                    this.ctx.playSelectionSound();
                });
        // THE TABS SCROLL HORIZONTALLY (WEIGHT 1 VIEWPORT); THE RESET ACTION IS A PINNED, NON-SCROLLING
        // SIBLING. TabStrip HOSTS BOTH AND DRAWS THE OPEN-FOLDER BASELINE ACROSS THE WHOLE STRIP.
        this.tabs = new TabScroll().spacing(6).height(MAX_PARENT).weight(1f);
        final TabStrip strip = new TabStrip();
        strip.spacing(12).width(MAX_PARENT).height(TAB_H).padding(new Spacing(12, 22, 0, 22));
        strip.add(this.tabs).add(this.reset);

        // SECTIONS — CLEAN LEFT COLUMN, MRL-STYLE: HEAD ROW (NEON BAR + LABEL) OVER A FLAT PLATE
        this.sectionColumn = Parent.column().spacing(2).width(MAX_PARENT);
        final Parent sections = Parent.column().spacing(10)
                .size(SIDEBAR_W, MAX_PARENT)
                .padding(new Spacing(8, 4, 8, 4))
                .background(AppTheme.alpha(AppTheme.BG_1, 150))
                .add(Parent.row().spacing(8).width(MAX_PARENT).height(24)
                        .add(new Box().size(4, 20).background(AppTheme.NEON).glow(AppTheme.NEON, 0.16f).gravity(Gravity.CENTER))
                        .add(new Text("SECTIONS").bold(true).scale(AppTheme.TEXT_SECTION).color(AppTheme.NEON).gravity(Gravity.CENTER)))
                .add(new ParentScroll().scrollbarWidth(3).size(MAX_PARENT, MAX_PARENT).add(this.sectionColumn));

        // SETTINGS PANE — MRL-STYLE HEAD ROW (NEON BAR + TITLE + DETAIL) OVER THE OPEN ROW LIST
        this.paneTitle = new Text().bold(true).color(AppTheme.NEON).scale(AppTheme.TEXT_SECTION).gravity(Gravity.CENTER);
        this.paneDetail = new Text().color(AppTheme.TEXT_FAINT).scale(AppTheme.TEXT_BODY).gravity(Gravity.CENTER).width(MAX_PARENT);
        this.list = new ListView<Setting>()
                .rowHeight(ROW_H)
                .spacing(8)
                .rowFactory((setting, index) -> {
                    final RowElement row = new RowElement(setting);
                    // HOVER SELECTS THE ROW (selectOnHover) AND CHIMES ON CHANGE, LIKE THE LEGACY SCREEN
                    row.onHover(v -> {
                        if (!v.selected()) this.ctx.playSelectionSound();
                    });
                    return row;
                })
                .onSelect((setting, index) -> this.activateBody(setting))
                .selectOnHover(true)
                .scrollbarWidth(4)
                // THE ROWS SELF-DRAW THEIR SELECTION FILL/GLOW, SO SUPPRESS THE LIST'S OWN HIGHLIGHTS
                .selectionColor(AppTheme.alpha(AppTheme.NEON_DARK, 0))
                .hoverColor(AppTheme.alpha(AppTheme.NEON_DARK, 0))
                .size(MAX_PARENT, MAX_PARENT);
        final Parent pane = Parent.column().spacing(10)
                .size(MAX_PARENT, MAX_PARENT)
                .padding(new Spacing(8, 0, 0, 18))
                .add(Parent.row().spacing(8).width(MAX_PARENT).height(24)
                        .add(new Box().size(4, 20).background(AppTheme.NEON).glow(AppTheme.NEON, 0.16f).gravity(Gravity.CENTER))
                        .add(this.paneTitle)
                        .add(this.paneDetail))
                .add(this.list);

        // CONTENT — SECTIONS PLATE | HAIRLINE RULE | OPEN ROWS, ALL DIRECTLY OVER THE SHELL BACKGROUND
        return Parent.column().size(MAX_PARENT, MAX_PARENT)
                .add(this.header)
                .add(strip)
                .add(Parent.row().size(MAX_PARENT, MAX_PARENT)
                        .padding(new Spacing(14, 22, 10, 22))
                        .add(sections)
                        .add(new Box().size(1, MAX_PARENT).background(AppTheme.STROKE_BRIGHT))
                        .add(pane));
    }

    @Override
    public void onEnter() {
        super.onEnter(); // ensureBuilt() — CONSTRUCTS THE TREE ONCE
        this.rebuildSpecs();
        this.syncNav();
        this.populateRows();
    }

    @Override
    protected void onUpdate() {
        final SettingSection section = this.activeSection();
        // THE HEADER SUBTITLE SHOWS THE ACTIVE TREE PATH ("engines" OR "group1 / group2")
        this.header.sub(section == null ? "" : section.path.toLowerCase(Locale.ROOT));
        this.paneTitle.text(section == null ? "" : section.name.toUpperCase(Locale.ROOT));
        this.paneDetail.text(section == null ? "" : section.detail);
        // FLUSH THE PER-GESTURE SEEKBAR SAVE ONCE THE POINTER RELEASED (LEGACY saveAfterDrag SEMANTICS),
        // PERSISTING THE SPEC THAT WAS ACTUALLY MUTATED — NOT activeSpec(), WHICH MAY HAVE SWITCHED MID-GESTURE
        if (this.dragSave && !this.ctx.mouseDown) {
            this.dragSave = false;
            final SettingSpec spec = this.dragSaveSpec;
            this.dragSaveSpec = null;
            this.saveSpec(spec);
        }
    }

    @Override
    public boolean continuous() {
        // KEEP PAINTING WHILE ANY SPEC'S SAVE PULSES ITS TAB PIP, WHILE A CARET BLINKS, OR UNTIL A
        // DRAG-SAVE FLUSHES
        for (final SaveMachine save: this.saves.values()) {
            if (save.state == SaveState.SAVING || save.state == SaveState.RESAVING) return true;
        }
        return this.textInputActive() || this.dragSave;
    }

    @Override
    protected void onDraw(final Canvas canvas) {
        // THE HOVERED ROW REPUBLISHES ITS DESCRIPTION WHILE THE TREE DRAWS; THE TOOLTIP THEN PAINTS
        // LAST SO IT FLOATS ABOVE EVERYTHING (ROWS, SECTION COLUMN AND DROPDOWN POPUPS) WITHOUT EVER
        // ENTERING INPUT DISPATCH — IT IS PURE PAINT, HENCE INPUT-TRANSPARENT BY CONSTRUCTION
        this.hoverNote = null;
        super.onDraw(canvas);
        final String note = this.hoverNote;
        // NO NOTE, NO TOOLTIP; HOVER IS FROZEN DURING A TEXT EDIT, SO THE TOOLTIP HIDES THERE TOO
        if (note == null || this.editField != null) return;
        // AMBER CURSOR TOOLTIP — GREEDY WORD WRAP INTO THE WIDTH BUDGET, POSITIONED AT A SMALL OFFSET
        // FROM THE CURSOR AND CLAMPED TO THE WINDOW EDGES (FLIPS ABOVE THE CURSOR NEAR THE BOTTOM)
        final int pad = 10;
        final int maxW = Math.min(340, canvas.windowWidth() - pad * 2 - 8);
        final List<String> lines = new ArrayList<>();
        String line = "";
        int textW = 0;
        for (String word: note.split(" ")) {
            if (word.isEmpty()) continue;
            if (canvas.textWidth(word, AppTheme.TEXT_BODY, false) > maxW) {
                word = canvas.text().truncateToWidth(word, maxW, AppTheme.TEXT_BODY);
            }
            final String joined = line.isEmpty() ? word : line + " " + word;
            if (!line.isEmpty() && canvas.textWidth(joined, AppTheme.TEXT_BODY, false) > maxW) {
                textW = Math.max(textW, canvas.textWidth(line, AppTheme.TEXT_BODY, false));
                lines.add(line);
                line = word;
            } else {
                line = joined;
            }
        }
        if (!line.isEmpty()) {
            textW = Math.max(textW, canvas.textWidth(line, AppTheme.TEXT_BODY, false));
            lines.add(line);
        }
        if (lines.isEmpty()) return;
        final int lineH = canvas.textHeight(AppTheme.TEXT_BODY, false) + 4;
        final int boxW = textW + pad * 2;
        final int boxH = lines.size() * lineH - 4 + pad * 2;
        int tx = (int) this.ctx.mouseX + 14;
        int ty = (int) this.ctx.mouseY + 18;
        if (tx + boxW > canvas.windowWidth() - 4) tx = canvas.windowWidth() - 4 - boxW;
        if (ty + boxH > canvas.windowHeight() - 4) ty = (int) this.ctx.mouseY - boxH - 10;
        tx = Math.max(4, tx);
        ty = Math.max(4, ty);
        canvas.fill(tx, ty, boxW, boxH, AppTheme.alpha(AppTheme.BG_1, 244));
        canvas.stroke(tx, ty, boxW, boxH, AppTheme.AMBER, 1f);
        canvas.glow(tx, ty, boxW, boxH, 0f, AppTheme.AMBER, 0.14f);
        int lineY = ty + pad;
        for (final String row: lines) {
            canvas.text(row, tx + pad, lineY, AppTheme.TEXT, AppTheme.TEXT_BODY, false);
            lineY += lineH;
        }
    }

    @Override
    public List<Keybind> keybinds() {
        if (this.editField != null) {
            return List.of(
                    new Keybind("ENTER", "Commit"),
                    new Keybind("BACKSPACE", "Delete"),
                    new Keybind("ESC", "Cancel"));
        }
        return List.of(
                new Keybind("UP/DOWN", "Field"),
                new Keybind("LEFT/RIGHT", "Change"),
                new Keybind("TAB", "Spec"),
                new Keybind("PGUP/PGDN", "Section"),
                new Keybind("R", "Reset"),
                new Keybind("ESC", "Back"));
    }

    // ==========================================================================
    // INPUT — TREE DISPATCH FIRST, THEN THE SCREEN SHORTCUTS (GLFW_RELEASE, LEGACY SEMANTICS)
    // ==========================================================================

    @Override
    public boolean dispatchKey(final int key, final int action) {
        // AN OPEN ENUM DROPDOWN MENU IS MODAL FOR THE KEYBOARD (LEGACY PARITY): IT OWNS ITS DISMISS KEYS
        // AND THE SCREEN SWALLOWS EVERYTHING ELSE SO NO SHORTCUT FIRES UNDER THE MENU. BECAUSE THE MENU
        // CLOSES ON THE ESC/ENTER *PRESS*, popupKey CARRIES THAT KEY TO ITS RELEASE SO THE SCREEN'S BACK /
        // ACTIVATE NEVER DOUBLE-FIRES ON THE FOLLOW-UP RELEASE.
        final boolean popupOpen = !this.overlay().children().isEmpty();
        if (super.dispatchKey(key, action)) { // POPUP DISMISS / FOCUSED FIELD BACKSPACE
            if (popupOpen && action != GLFW_RELEASE) this.popupKey = key;
            return true;
        }
        if (popupOpen) return true;
        // SWALLOW ONLY THE RELEASE PAIRED WITH THE POPUP-DISMISS PRESS. A FRESH PRESS CANCELS A STALE
        // PENDING SWALLOW (E.G. THE MENU WAS CLOSED BY MOUSE, SO THE PAIRED RELEASE NEVER ARRIVED) —
        // OTHERWISE THAT NEXT KEYSTROKE WOULD BE SILENTLY EATEN
        if (action == GLFW_RELEASE) {
            if (key == this.popupKey) {
                this.popupKey = -1;
                return true;
            }
        } else if (this.popupKey != -1) {
            this.popupKey = -1;
        }
        if (this.editField != null) {
            // EDIT MODE SWALLOWS THE KEYBOARD; COMMIT/CANCEL ON RELEASE LIKE THE LEGACY SCREEN
            if (action == GLFW_RELEASE) {
                if (key == GLFW_KEY_ESCAPE) {
                    this.closeEdit();
                } else if (key == GLFW_KEY_ENTER || key == GLFW_KEY_KP_ENTER) {
                    this.commitEdit();
                }
            }
            return true;
        }
        if (action != GLFW_RELEASE) return false;
        switch (key) {
            case GLFW_KEY_UP -> {
                this.list.moveSelection(-1);
                this.ctx.playSelectionSound();
            }
            case GLFW_KEY_DOWN -> {
                this.list.moveSelection(1);
                this.ctx.playSelectionSound();
            }
            case GLFW_KEY_PAGE_UP -> this.moveSection(-1);
            case GLFW_KEY_PAGE_DOWN -> this.moveSection(1);
            case GLFW_KEY_TAB -> this.switchSpec(this.activeSpecIndex + 1);
            case GLFW_KEY_LEFT -> this.adjustSelected(-1);
            case GLFW_KEY_RIGHT -> this.adjustSelected(1);
            case GLFW_KEY_ENTER, GLFW_KEY_KP_ENTER -> this.activateSelected();
            case GLFW_KEY_R -> this.resetActiveSection();
            case GLFW_KEY_ESCAPE -> this.navigator.accept(HomeScreen.Action.BACK);
            default -> {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean dispatchClick(final double mx, final double my) {
        final boolean handled = super.dispatchClick(mx, my);
        // RECONCILE TEXT FOCUS AFTER THE CLICK — AT MOST ONE FIELD STAYS FOCUSED AND BECOMES THE ACTIVE EDIT
        TextField focused = null;
        for (final TextField field: this.textFields) {
            if (!field.focused()) continue;
            if (focused == null && field.contains(mx, my)) {
                focused = field;
            } else {
                field.focused(false);
            }
        }
        if (focused != this.editField) {
            if (focused == null) {
                this.closeEdit(); // CLICKED AWAY — DISCARD THE EDIT, THE VALUE RE-SYNCS FROM THE SETTING
            } else {
                this.editField = focused;
                this.editSetting = (Setting) focused.tag();
                focused.value(this.editSetting.editValue());
                this.invalidate();
            }
        }
        return handled;
    }

    @Override
    public boolean dispatchHover(final double mx, final double my) {
        // LEGACY PARITY — POINTER HOVER IS FROZEN WHILE A TEXT EDIT IS ACTIVE
        if (this.editField != null) return false;
        return super.dispatchHover(mx, my);
    }

    @Override
    public Element<?> dispatchPress(final double mx, final double my) {
        // LEGACY PARITY — NO DRAG CAPTURE (SEEKBARS) WHILE A TEXT EDIT IS ACTIVE
        return this.editField != null ? null : super.dispatchPress(mx, my);
    }

    // ==========================================================================
    // SPEC / SECTION NAVIGATION
    // ==========================================================================

    private void rebuildSpecs() {
        this.specs.clear();
        // TABS 0/1 — THE SHELL SPEC (WITH ITS INJECTED ENGINE/UI-SCALE ROWS) AND THE INSTANCE CONFIG
        this.specs.add(this.buildAppSpec());
        this.specs.add(this.buildInstanceSpec());

        // EVERY OTHER SPEC WATERCONFIG NOW EXPOSES — TABS 0/1 ALREADY COVER THE SHELL AND INSTANCE, SO
        // SKIP THOSE TWO. SORTED BY NAME FOR A STABLE TAB ORDER ACROSS RE-ENTRIES (specs() IS BACKED BY
        // A HASH MAP AND HAS NO INHERENT ORDER).
        final List<ConfigSpec> registered = new ArrayList<>(WaterConfig.specs());
        registered.sort(Comparator.comparing(ConfigSpec::name));
        for (final ConfigSpec cs: registered) {
            final String name = cs.name();
            if (AppConfig.ID.equals(name) || WaterMedia.ID.equals(name)) continue;
            this.specs.add(this.buildGenericSpec(titleCase(name), fileName(cs), cs, "settings", name));
        }

        // REVERSE-ENGINEER EXTERNAL MOD CONFIG FILES INTO EDITABLE TABS — ATTEMPTED, SKIPPED WHEN ABSENT
        // OR UNPARSEABLE (WaterConfig.reverseSpec RETURNS null)
        for (final String file: REVERSE_FILES) {
            final SettingSpec reverse = this.buildReverseSpec(file);
            if (reverse != null) this.specs.add(reverse);
        }

        if (this.activeSpecIndex >= this.specs.size()) this.activeSpecIndex = Math.max(0, this.specs.size() - 1);
        this.clampSelection();
    }

    // ONE SAVE MACHINE PER SPEC IDENTITY, REUSED ACROSS REBUILDS SO AN IN-FLIGHT SAVE KEEPS ITS
    // COALESCING GATE. KEYS ARE STABLE PER LOGICAL SPEC: THE SHELL/INSTANCE IDS, EACH REGISTERED SPEC'S
    // NAME, AND EACH REVERSE-SPEC'D FILE NAME.
    private SaveMachine saveFor(final String key) {
        return this.saves.computeIfAbsent(key, k -> new SaveMachine());
    }

    // THE FILE NAME BACKING A SPEC (SHOWN AS THE TAB SUBTITLE)
    private static String fileName(final ConfigSpec spec) {
        return String.valueOf(spec.path().getFileName());
    }

    // (RE)FILLS THE SPEC TAB STRIP AND THE ACTIVE SPEC'S SECTION LIST
    private void syncNav() {
        this.tabs.clear();
        for (int i = 0; i < this.specs.size(); i++) {
            final int index = i;
            final SettingSpec spec = this.specs.get(i);
            this.tabs.add(new SpecTab(index, spec.title, spec.subtitle, spec.iconName)
                    .onClick(v -> {
                        this.switchSpec(index);
                        this.ctx.playSelectionSound();
                    }));
        }
        // KEEP THE ACTIVE TAB IN VIEW AFTER A REBUILD/SWITCH (THE STRIP MAY BE SCROLLED PAST IT)
        this.tabs.followActive();
        this.sectionColumn.clear();
        final SettingSpec active = this.activeSpec();
        if (active != null) {
            for (int i = 0; i < active.sections.size(); i++) {
                final int index = i;
                this.sectionColumn.add(new SectionItem(index, active.sections.get(i))
                        .onClick(v -> {
                            this.switchSection(index);
                            this.ctx.playSelectionSound();
                        }));
            }
        }
        this.invalidate();
    }

    // (RE)LOADS THE ACTIVE SECTION'S SETTINGS INTO THE ROW LIST AND RESETS THE SELECTION TO THE TOP
    private void populateRows() {
        // DROP ANY OPEN ENUM DROPDOWN MENU — THE ROWS (AND THEIR DROPDOWNS) ARE ABOUT TO BE REBUILT
        this.overlay().clear();
        final SettingSection section = this.activeSection();
        this.editField = null;
        this.editSetting = null;
        this.textFields.clear();
        this.list.items(section == null ? List.of() : section.settings);
        this.list.selection(section == null || section.settings.isEmpty() ? -1 : 0);
    }

    private void switchSpec(final int index) {
        if (this.specs.isEmpty()) return;
        this.activeSpecIndex = Math.floorMod(index, this.specs.size());
        this.activeSectionIndex = 0;
        this.closeEdit();
        this.syncNav();
        this.populateRows();
    }

    private void switchSection(final int index) {
        final SettingSpec spec = this.activeSpec();
        if (spec == null || spec.sections.isEmpty()) return;
        this.activeSectionIndex = Math.max(0, Math.min(spec.sections.size() - 1, index));
        this.closeEdit();
        this.populateRows();
    }

    private void moveSection(final int delta) {
        final SettingSpec spec = this.activeSpec();
        if (spec == null || spec.sections.isEmpty()) return;
        this.switchSection(Math.floorMod(this.activeSectionIndex + delta, spec.sections.size()));
        this.ctx.playSelectionSound();
    }

    private void clampSelection() {
        final SettingSpec spec = this.activeSpec();
        if (spec == null || spec.sections.isEmpty()) {
            this.activeSectionIndex = 0;
            return;
        }
        this.activeSectionIndex = Math.max(0, Math.min(spec.sections.size() - 1, this.activeSectionIndex));
    }

    private SettingSpec activeSpec() {
        return this.specs.isEmpty() ? null : this.specs.get(Math.max(0, Math.min(this.specs.size() - 1, this.activeSpecIndex)));
    }

    private SettingSection activeSection() {
        final SettingSpec spec = this.activeSpec();
        return spec == null || spec.sections.isEmpty()
                ? null
                : spec.sections.get(Math.max(0, Math.min(spec.sections.size() - 1, this.activeSectionIndex)));
    }

    // ==========================================================================
    // SETTING ACTIVATION — MOUSE BODY CLICKS, KEYBOARD ADJUST/ACTIVATE, WIDGET CHANGES
    // ==========================================================================

    // RUNS A SETTING MUTATION FROM A WIDGET, THEN AUTOSAVES + CHIMES; AN INVALID VALUE IS REVERTED BY THE
    // SETTING ITSELF (setValue RESTORES THE PREVIOUS VALUE) AND THE WIDGET RE-SYNCS ON THE NEXT UPDATE PASS
    private void apply(final BooleanSupplier mutation) {
        try {
            if (mutation.getAsBoolean()) {
                this.autosave();
                this.ctx.playSelectionSound();
            }
        } catch (final RuntimeException e) {
            this.invalidate();
        }
    }

    // SEEKBAR TICK — APPLY THE PERCENT IMMEDIATELY BUT DEFER THE SAVE TO THE END OF THE GESTURE,
    // CHIMING ONLY ON THE FIRST TICK (THE LEGACY PRESS CHIME)
    private void seek(final Setting setting, final float pct) {
        try {
            if (setting.percent(pct)) {
                if (!this.dragSave) this.ctx.playSelectionSound();
                this.dragSave = true;
                // PIN THE SPEC OWNING THIS SEEKBAR NOW — activeSpec() COULD CHANGE BEFORE THE RELEASE FLUSH
                this.dragSaveSpec = this.activeSpec();
            }
        } catch (final RuntimeException e) {
            this.dragSave = false;
            this.dragSaveSpec = null;
            this.invalidate();
        }
    }

    // MOUSE CLICK ON A ROW BODY (OUTSIDE ITS CONTROL) — ACTIVATES THE ROW LIKE THE LEGACY SCREEN
    private void activateBody(final Setting setting) {
        this.ctx.playSelectionSound();
        if (setting == null || !setting.mutable) return;
        if (setting.editableText()) {
            this.beginEdit(setting);
            return;
        }
        try {
            if (setting.adjust(1, 1)) this.autosave();
        } catch (final RuntimeException e) {
            this.invalidate();
        }
    }

    private void adjustSelected(final int delta) {
        final Setting setting = this.list.selectedItem();
        if (setting == null || !setting.mutable) return;
        try {
            if (setting.adjust(delta, this.ctx.ctrlDown ? 10 : 1)) {
                this.autosave();
                this.ctx.playSelectionSound();
            } else if (setting.editableText()) {
                this.beginEdit(setting);
            }
        } catch (final RuntimeException e) {
            this.invalidate();
        }
    }

    private void activateSelected() {
        final Setting setting = this.list.selectedItem();
        if (setting == null || !setting.mutable) return;
        if (setting.editableText()) {
            this.beginEdit(setting);
            return;
        }
        this.adjustSelected(1);
    }

    private void resetActiveSection() {
        final SettingSection section = this.activeSection();
        if (section == null) return;
        try {
            for (final Setting setting: section.settings) {
                if (setting.mutable) setting.reset();
            }
            this.autosave();
        } catch (final RuntimeException e) {
            this.invalidate();
        }
    }

    // ==========================================================================
    // TEXT EDITING — THE TextField WIDGET OWNS TYPING; THE SCREEN OWNS COMMIT/CANCEL
    // ==========================================================================

    private void beginEdit(final Setting setting) {
        for (final TextField field: this.textFields) {
            if (field.tag() != setting) continue;
            if (this.editField != null && this.editField != field) this.editField.focused(false);
            this.editField = field;
            this.editSetting = setting;
            field.value(setting.editValue()).focused(true);
            this.invalidate();
            return;
        }
    }

    private void commitEdit() {
        if (this.editField == null || this.editSetting == null) {
            this.closeEdit();
            return;
        }
        try {
            this.editSetting.commit(this.editField.value());
            this.closeEdit();
            this.autosave();
        } catch (final RuntimeException e) {
            // INVALID INPUT — STAY IN EDIT MODE SO THE USER CAN FIX THE VALUE (LEGACY BEHAVIOR)
            this.invalidate();
        }
    }

    private void closeEdit() {
        if (this.editField != null) this.editField.focused(false);
        this.editField = null;
        this.editSetting = null;
        this.invalidate();
    }

    // ==========================================================================
    // ENUM CONTROL PICK — SEGMENTED CONTROL FOR SMALL SETS, DROPDOWN (ATTACH MENU) FOR THE REST
    // ==========================================================================

    // WHETHER AN ENUM SETTING USES THE DROPDOWN INSTEAD OF A SEGMENTED CONTROL: MORE THAN 4 OPTIONS, OR
    // SEGMENTS THAT WOULD BE WIDER THAN THE CONTROL BUDGET
    private boolean needsDropdown(final Setting setting) {
        if (!setting.isEnumControl()) return false;
        // THE RENDER-MODE SELECTOR STAYS A SLIDE-SWITCH (SEGMENTED) EVEN WITH ITS 4 OPTIONS — NEVER A DROPDOWN
        if (RENDER_KEY.equals(setting.key)) return false;
        final List<String> options = setting.optionLabels();
        if (options.size() > 4) return true;
        int segW = 0;
        for (final String option: options) {
            segW += SegmentedControl.segmentWidth(this.text, option.toUpperCase(Locale.ROOT), AppTheme.TEXT_BODY);
        }
        return segW > CONTROL_W;
    }

    // ==========================================================================
    // SPEC MODELS — ONE REFLECTIVE TREE BUILDER FOR BOTH WATERCONFIG SPECS (SHELL + INSTANCE)
    // ==========================================================================

    private SettingSpec buildAppSpec() {
        // THE SHELL SPEC IS AN ANNOTATED WATERCONFIG CLASS (AppConfig) REFLECTED THROUGH THE EXACT SAME
        // PATH AS THE INSTANCE SPEC. EVERY ROW IS A REAL OPTION — THE OLD READ-ONLY DIAGNOSTICS ARE GONE.
        final ConfigSpec configSpec = AppConfig.spec();
        final String file = configSpec == null ? "runtime shell" : String.valueOf(configSpec.path().getFileName());
        final SettingSpec spec = new SettingSpec("App", file, configSpec != null, configSpec,
                () -> AppConfig.apply(this.ctx), this.saveFor(AppConfig.ID), null);
        if (configSpec != null) this.collectSections(configSpec, spec);

        // THE SPEC'S "ENGINES" GROUP ALSO HOSTS THE TWO STRICTLY NON-PORTABLE RUNTIME ROWS: THE RENDER
        // ENGINE AND THE UI SCALE (THEIR engine.cfg/uiscale.cfg STORES ARE READ BY THE LAUNCHER AND THE
        // EARLY BOOT BEFORE ANY SPEC EXISTS). WITHOUT A LOADED SPEC THE NODE IS CREATED RUNTIME-ONLY SO
        // BOTH ROWS STAY REACHABLE.
        SettingSection engines = null;
        for (final SettingSection section: spec.sections) {
            if (section.depth == 0 && "Engines".equalsIgnoreCase(section.name)) {
                engines = section;
                break;
            }
        }
        if (engines == null) {
            engines = new SettingSection("Engines", "Engine backends: render, interface scale and audio", "engines", 0);
            spec.sections.add(engines);
        }
        engines.settings.add(0, new RuntimeEnumSetting<>("Render", RENDER_KEY, "ENUM",
                "Render mode. OpenGL/Vulkan play in-app; VK+AWT and VK+JavaFX run the UI on Vulkan and pop the player out into a native window. Engine changes apply live; the popup target applies to the next media opened. Stored in engine.cfg + playermode.cfg.",
                RenderMode.values(),
                () -> RenderMode.of(RenderSystem.engineKind(), WaterMediaApp.playerTarget()),
                mode -> {
                    // PERSIST BOTH HALVES, HOT-SWAP THE ENGINE IN PLACE, AND ROUTE FUTURE MRLs TO THE POPUP
                    RenderSystem.saveEnginePreference(mode.engine());
                    RenderSystem.savePlayerTarget(mode.target());
                    WaterMediaApp.applyPlayerTarget(mode.target());
                    // VK+JavaFX WITHOUT JAVAFX ON THE CLASSPATH: THE CONFIG IS SAVED, SO EXIT AND LET THE
                    // BOOTSTRAP PULL JAVAFX AND RELAUNCH (ONCE CACHED, LATER SWITCHES NEED NO RESTART)
                    if (mode.target() == PlayerTarget.JFX && !WaterMediaApp.javafxAvailable()) {
                        WaterMediaApp.requestRelaunch();
                        return;
                    }
                    if (mode.engine() != RenderSystem.engineKind()) WaterMediaApp.requestEngineSwap(mode.engine());
                },
                RenderMode.OPENGL,
                RenderMode::label,
                // VULKAN-BACKED MODES NEED A VULKAN LOADER; OPENGL IS ALWAYS AVAILABLE
                mode -> mode.engine() != RenderSystem.Engine.VULKAN || RenderSystem.vulkanAvailable()));
        engines.settings.add(1, new RuntimeEnumSetting<>("UI scale", "app.engines.uiScale", "ENUM",
                "Global interface magnification. AUTO derives it from the monitor's DPI and resolution. Applies live. Stored in uiscale.cfg, read at boot before any config spec exists.",
                UiScale.values(),
                () -> UiScale.fromPreference(RenderSystem.uiScalePreference()),
                value -> {
                    RenderSystem.saveUiScalePreference(value.preference());
                    // RE-READS THE PREFERENCE AND APPLIES IT (AUTO RECOMPUTES THE MONITOR-DERIVED FACTOR)
                    WaterMediaApp.reapplyUiScale();
                },
                UiScale.AUTO,
                scale -> scale.label));
        return spec;
    }

    private SettingSpec buildInstanceSpec() {
        // WATERCONFIG NOW EXPOSES THE REGISTERED SPEC DIRECTLY — NO MORE REFLECTION INTO ITS REGISTRY
        final ConfigSpec configSpec = WaterConfig.spec(WaterMedia.ID);
        if (configSpec == null) {
            final SettingSpec spec = new SettingSpec("Instance config", "watermedia.toml", true, null, null,
                    this.saveFor(WaterMedia.ID), null);
            final SettingSection unavailable = new SettingSection("Unavailable",
                    "WaterConfig did not expose the active spec", "unavailable", 0);
            unavailable.settings.add(new ReadOnlySetting("WaterMediaConfig", "watermedia", "STATE",
                    "The app could not read the registered WaterConfig spec.",
                    () -> WaterConfig.isRegistered(WaterMedia.ID) ? "REGISTERED" : "NOT REGISTERED"));
            spec.sections.add(unavailable);
            return spec;
        }
        return this.buildGenericSpec("Instance config", fileName(configSpec), configSpec, null, WaterMedia.ID);
    }

    // BUILDS A PLAIN WATERCONFIG SPEC TAB (INSTANCE, WATERCONFIG-EXPOSED OR REVERSE-SPEC'D): ITS SECTION
    // TREE COMES FROM collectSections, WITH A READ-ONLY PLACEHOLDER WHEN THE SPEC EXPOSES NO FIELDS
    private SettingSpec buildGenericSpec(final String title, final String subtitle, final ConfigSpec configSpec,
                                         final String icon, final String saveKey) {
        final SettingSpec spec = new SettingSpec(title, subtitle, true, configSpec, null, this.saveFor(saveKey), icon);
        this.collectSections(configSpec, spec);
        if (spec.sections.isEmpty()) {
            final SettingSection empty = new SettingSection("Empty", "No fields were discovered", "empty", 0);
            empty.settings.add(new ReadOnlySetting(titleCase(configSpec.name()), configSpec.name(), "STATE",
                    "No configurable fields were discovered in this spec.", () -> "EMPTY"));
            spec.sections.add(empty);
        }
        return spec;
    }

    // ATTEMPTS TO REVERSE-ENGINEER AN EXTERNAL MOD CONFIG FILE FROM THE CONFIG DIR INTO AN EDITABLE SPEC.
    // RETURNS null WHEN THE FILE IS ABSENT/UNPARSEABLE (reverseSpec) OR EXPOSES NO EDITABLE SECTIONS, SO
    // THE TAB SIMPLY DOES NOT APPEAR. THE FILE NAME KEYS BOTH THE TAB SUBTITLE AND ITS SAVE MACHINE.
    private SettingSpec buildReverseSpec(final String file) {
        final Path path = WaterConfig.getPath().resolve(file);
        // REUSE THE LAST PARSE WHILE THE FILE'S mtime IS UNCHANGED (ABSENT/UNREADABLE = -1, WHICH ALSO
        // CACHES THE "SKIP" DECISION). A SAVE BUMPS THE mtime, SO THE NEXT ENTRY RE-PARSES FRESH VALUES.
        long mtime = -1L;
        try {
            mtime = Files.getLastModifiedTime(path).toMillis();
        } catch (final IOException e) {
            // ABSENT/UNREADABLE — mtime STAYS -1; reverseSpec BELOW RETURNS null AND THE TAB IS SKIPPED
        }
        final ReverseSpec cached = this.reverseCache.get(file);
        final ConfigSpec configSpec;
        if (cached != null && cached.mtime() == mtime) {
            configSpec = cached.spec();
        } else {
            configSpec = WaterConfig.reverseSpec(path);
            this.reverseCache.put(file, new ReverseSpec(mtime, configSpec));
        }
        if (configSpec == null) return null;
        final SettingSpec spec = new SettingSpec(titleCase(configSpec.name()), file, true, configSpec, null,
                this.saveFor(file), "folder");
        this.collectSections(configSpec, spec);
        return spec.sections.isEmpty() ? null : spec;
    }

    // BUILDS THE SECTION TREE OF A WATERCONFIG SPEC: THE SPEC ROOT IS A "HOME" NODE NAMED AFTER THE SPEC
    // (LISTED FIRST, HIDDEN WHEN THE ROOT HAS NO DIRECT SETTINGS), THEN EVERY GROUP BECOMES A DFS-ORDERED
    // NODE — ONE INDENT LEVEL PER NESTING LEVEL. EACH NODE LISTS ONLY ITS DIRECT FIELDS; DESCENDANTS ARE
    // REACHED THROUGH THEIR OWN NODES.
    private void collectSections(final ConfigSpec configSpec, final SettingSpec spec) {
        final String specName = titleCase(configSpec.name());
        final SettingSection home = new SettingSection(specName, groupDetail(configSpec, "root settings"), specName, 0);
        final Collection<IConfigField<?, ?>> fields = configSpec.getFields();
        for (final IConfigField<?, ?> field: fields) {
            if (!(field instanceof ConfigGroup)) home.settings.add(new ConfigSetting(field, field.name()));
        }
        if (!home.settings.isEmpty()) spec.sections.add(home);
        for (final IConfigField<?, ?> field: fields) {
            if (field instanceof ConfigGroup child) this.collectGroup(child, 0, "", "", spec);
        }
    }

    private void collectGroup(final ConfigGroup group, final int depth, final String parentKey,
                              final String parentPath, final SettingSpec spec) {
        final String key = parentKey.isEmpty() ? group.name() : parentKey + "." + group.name();
        final String path = parentPath.isEmpty() ? group.name() : parentPath + " / " + group.name();
        final SettingSection section = new SettingSection(titleCase(group.name()), groupDetail(group, key), path, depth);
        final Collection<IConfigField<?, ?>> fields = group.getFields();
        for (final IConfigField<?, ?> field: fields) {
            if (!(field instanceof ConfigGroup)) section.settings.add(new ConfigSetting(field, key + "." + field.name()));
        }
        // THE NODE STAYS EVEN WITH NO DIRECT FIELDS — IT IS THE TREE PATH TO ITS CHILD GROUPS
        spec.sections.add(section);
        for (final IConfigField<?, ?> field: fields) {
            if (field instanceof ConfigGroup child) this.collectGroup(child, depth + 1, key, path, spec);
        }
    }

    private static String groupDetail(final ConfigGroup group, final String fallback) {
        for (final String comment: group.comments()) {
            if (comment != null && !comment.isBlank()) return comment.trim();
        }
        return fallback;
    }

    // ==========================================================================
    // AUTOSAVE — LIVE-APPLIES THE SHELL SPEC, THEN PERSISTS OFF-THREAD; EACH SPEC'S SaveMachine
    // SURFACES ITS STATE THROUGH THAT SPEC'S TAB PIP (SEE SpecTab)
    // ==========================================================================

    private void autosave() {
        this.saveSpec(this.activeSpec());
    }

    // PERSISTS ONE SPECIFIC SPEC — THE ACTIVE ONE FOR IMMEDIATE EDITS (autosave), OR THE SPEC PINNED AT
    // SEEKBAR-ARM TIME FOR THE DEFERRED DRAG FLUSH (SEE onUpdate)
    private void saveSpec(final SettingSpec spec) {
        if (spec == null) return;
        // PUSH THE FRESH VALUES INTO THE LIVE APP STATE (SHELL SPEC) BEFORE PERSISTING THEM
        if (spec.applier != null) spec.applier.run();
        final SaveMachine save = spec.save;
        if (!spec.persistent || spec.configSpec == null) {
            save.failures = 0;
            save.state = SaveState.READY;
            return;
        }
        // A SAVE OF THIS SPEC IS ALREADY IN FLIGHT — COALESCE THIS CHANGE INTO ONE FOLLOW-UP SAVE
        // RATHER THAN SPAWNING A SECOND WRITER AGAINST THE SAME CONFIG FILE. THE MACHINES ARE PER-SPEC,
        // SO A SAVE OF THE OTHER SPEC (ANOTHER FILE) NEVER BLOCKS OR SWALLOWS THIS ONE.
        if (save.saving) {
            save.resavePending = true;
            return;
        }
        this.startSave(spec);
    }

    private void startSave(final SettingSpec spec) {
        final SaveMachine save = spec.save;
        save.saving = true;
        save.state = save.failures > 0 ? SaveState.RESAVING : SaveState.SAVING;
        this.ctx.requestRender();
        final ConfigSpec configSpec = spec.configSpec;
        // RUN THE REFLECTIVE DISK WRITE OFF THE RENDER THREAD, THEN PUBLISH THE OUTCOME BACK ON IT VIA
        // ctx.execute SO THE SAVE STATE STAYS RENDER-THREAD-CONFINED AND THE SAVING FRAME ACTUALLY RENDERS.
        ThreadTool.createStarted("WaterMedia-ConfigSave", () -> {
            ReflectiveOperationException error = null;
            try {
                if (CONFIG_SAVE == null) throw new NoSuchMethodException("ConfigSpec#save unavailable");
                CONFIG_SAVE.invoke(configSpec);
            } catch (final ReflectiveOperationException e) {
                error = e;
            }
            final ReflectiveOperationException outcome = error;
            this.ctx.execute(() -> this.finishSave(spec, outcome));
        });
    }

    private void finishSave(final SettingSpec spec, final ReflectiveOperationException error) {
        final SaveMachine save = spec.save;
        if (error == null) {
            save.failures = 0;
            save.state = SaveState.SAVED;
        } else {
            save.failures++;
            save.state = save.failures >= 2 ? SaveState.FAILED_TWICE : SaveState.FAILED_ONCE;
        }
        save.saving = false;
        this.invalidate();
        // A CHANGE ARRIVED WHILE SAVING — PERSIST IT NOW WITH ONE COALESCED FOLLOW-UP SAVE.
        if (save.resavePending) {
            save.resavePending = false;
            this.startSave(spec);
        }
    }

    // ==========================================================================
    // SMALL HELPERS
    // ==========================================================================

    private static String seekDisplay(final Setting setting, final String value) {
        final String suffix = setting.suffix();
        return suffix.isBlank() ? value : value + " " + suffix;
    }

    private static Color statusColor(final String value) {
        final String normalized = value == null ? "" : value.toUpperCase(Locale.ROOT);
        if (normalized.contains("READY") || normalized.contains("LOADED") || normalized.contains("AVAILABLE")) return AppTheme.GREEN;
        if (normalized.contains("ERROR") || normalized.contains("UNLOADED")) return AppTheme.RED;
        if (normalized.contains("ONLY") || normalized.contains("OFFLINE")) return AppTheme.AMBER;
        return AppTheme.TEXT_FAINT;
    }

    // TURNS A FIELD/GROUP IDENTIFIER INTO A DISPLAY NAME: _ AND - BECOME SPACES, camelCase BOUNDARIES
    // SPLIT INTO WORDS ("crtOverlay" -> "Crt Overlay", "pngUseBKGDChunk" -> "Png Use BKGD Chunk") AND
    // EVERY WORD IS CAPITALIZED — ROW NAMES RENDER VERBATIM NOW, SO THEY MUST READ AS WORDS
    private static String titleCase(final String value) {
        if (value == null || value.isBlank()) return "Settings";
        final String spaced = value.replace('_', ' ').replace('-', ' ');
        final StringBuilder out = new StringBuilder(spaced.length() + 4);
        boolean upper = true;
        for (int i = 0; i < spaced.length(); i++) {
            final char c = spaced.charAt(i);
            if (Character.isWhitespace(c)) {
                upper = true;
                out.append(c);
                continue;
            }
            if (i > 0 && Character.isUpperCase(c)) {
                final char prev = spaced.charAt(i - 1);
                final boolean nextLower = i + 1 < spaced.length() && Character.isLowerCase(spaced.charAt(i + 1));
                // BREAK ON lower->UPPER AND ON THE LAST CAPITAL OF AN ACRONYM RUN ("BKGDChunk" -> "BKGD Chunk")
                if (Character.isLowerCase(prev) || Character.isDigit(prev) || (Character.isUpperCase(prev) && nextLower)) {
                    out.append(' ');
                }
            }
            out.append(upper ? Character.toUpperCase(c) : c);
            upper = false;
        }
        return out.toString();
    }

    // ==========================================================================
    // CUSTOM ELEMENTS — CANVAS-ONLY DRAWING
    // ==========================================================================

    // SPEC TAB SCROLLER — A HORIZONTAL, CLIPPING VIEWPORT OVER THE FOLDER TABS. THE WHEEL SCROLLS IT AND
    // A SPEC SWITCH FOLLOWS THE ACTIVE TAB INTO VIEW; SCROLLED-OUT TABS ARE CLIPPED AND NON-INTERACTIVE.
    private final class TabScroll extends Group<TabScroll> {

        private static final int STEP = 48; // PX PER WHEEL NOTCH

        private int spacing;
        private int scrollX;
        private int maxScrollX;
        private int naturalWidth; // SUM OF TAB WIDTHS + GAPS — THE SCROLLABLE CONTENT WIDTH
        private boolean followActive; // ON A SPEC SWITCH, BRING THE ACTIVE TAB INTO VIEW ON THE NEXT LAYOUT

        private TabScroll spacing(final int gap) {
            this.spacing = Math.max(0, gap);
            return this;
        }

        private void followActive() {
            this.followActive = true;
        }

        @Override
        protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
            int total = 0;
            boolean first = true;
            for (final Element<?> child: this.children) {
                if (!child.visible()) continue;
                child.measure(innerAvailWidth, innerAvailHeight); // TABS WRAP THEIR WIDTH, FILL THE HEIGHT
                if (!first) total += this.spacing;
                first = false;
                total += child.measuredWidth();
            }
            this.naturalWidth = total;
            this.contentWidth = total;
            this.contentHeight = innerAvailHeight;
        }

        @Override
        protected void onLayout() {
            final int viewport = this.innerWidth();
            this.maxScrollX = Math.max(0, this.naturalWidth - viewport);
            // FOLLOW THE ACTIVE TAB BEFORE CLAMPING SO A JUST-SWITCHED, OFF-SCREEN TAB SNAPS INTO VIEW
            if (this.followActive) {
                this.followActive = false;
                this.scrollX = this.offsetForActive(viewport);
            }
            this.scrollX = Math.max(0, Math.min(this.scrollX, this.maxScrollX));
            int x = this.innerLeft() - this.scrollX;
            final int y = this.innerTop();
            boolean first = true;
            for (final Element<?> child: this.children) {
                if (!child.visible()) continue;
                if (!first) x += this.spacing;
                first = false;
                child.layout(x, y);
                x += child.measuredWidth();
            }
        }

        // MINIMAL OFFSET THAT BRINGS THE ACTIVE TAB FULLY INTO THE VIEWPORT (KEEPS THE CURRENT OFFSET WHEN
        // THE TAB IS ALREADY VISIBLE)
        private int offsetForActive(final int viewport) {
            int x = 0;
            boolean first = true;
            for (final Element<?> child: this.children) {
                if (!child.visible()) continue;
                if (!first) x += this.spacing;
                first = false;
                if (child instanceof SpecTab tab && tab.index == SettingsScreen.this.activeSpecIndex) {
                    final int right = x + child.measuredWidth();
                    if (x < this.scrollX) return x;
                    if (right > this.scrollX + viewport) return right - viewport;
                    return this.scrollX;
                }
                x += child.measuredWidth();
            }
            return this.scrollX;
        }

        @Override
        protected void onDraw(final Canvas canvas) {
            // CLIP TO THE VIEWPORT SO SCROLLED-OUT TABS ARE NOT PAINTED (A PARTIAL EDGE TAB HINTS AT MORE)
            canvas.pushClip(this.left, this.top, this.measuredWidth, this.measuredHeight);
            super.onDraw(canvas);
            canvas.popClip();
        }

        @Override
        public boolean dispatchScroll(final double mx, final double my, final double amount) {
            if (!this.contains(mx, my) || this.maxScrollX <= 0) return false;
            // VERTICAL WHEEL DRIVES THE HORIZONTAL OFFSET — THE USUAL STRIP-SCROLL GESTURE
            final int next = Math.max(0, Math.min(this.maxScrollX, this.scrollX - (int) (amount * STEP)));
            if (next != this.scrollX) {
                this.scrollX = next;
                this.invalidate();
            }
            return true;
        }

        @Override
        public boolean dispatchClick(final double mx, final double my) {
            // TABS STAY CHILDREN WHEN SCROLLED OUT, SO GATE HITS ON THE VIEWPORT RECT
            return this.contains(mx, my) && super.dispatchClick(mx, my);
        }

        @Override
        public boolean dispatchHover(final double mx, final double my) {
            if (!this.contains(mx, my)) {
                for (final Element<?> child: this.children) child.clearHover();
                this.hovered = false;
                return false;
            }
            return super.dispatchHover(mx, my);
        }
    }

    // SPEC TAB STRIP — HOSTS THE SCROLLABLE TABS AND THE PINNED RESET ACTION, THEN CLOSES THE ROW WITH A
    // BASELINE RULE BROKEN UNDER THE ACTIVE TAB SO IT READS AS AN OPEN FOLDER INTO THE CONTENT BELOW
    private final class TabStrip extends Parent {

        private TabStrip() {
            super(Orientation.HORIZONTAL);
        }

        @Override
        protected void onDraw(final Canvas canvas) {
            super.onDraw(canvas);
            // BASELINE DRAWN AFTER THE CHILDREN SO IT SEALS THE INACTIVE TAB BOTTOMS. THE ACTIVE TAB LIVES
            // INSIDE THE SCROLLER, SO ITS GAP IS CLAMPED TO THE SCROLLER VIEWPORT — A SCROLLED-OUT TAB
            // NEVER BREAKS THE RULE OUTSIDE THE VISIBLE AREA.
            final int y = this.top + this.measuredHeight - 1;
            final TabScroll scroll = SettingsScreen.this.tabs;
            final int viewL = scroll.left();
            final int viewR = scroll.left() + scroll.measuredWidth();
            int gapL = -1;
            int gapR = -1;
            for (final Element<?> child: scroll.children()) {
                if (child instanceof SpecTab tab && tab.index == SettingsScreen.this.activeSpecIndex) {
                    gapL = Math.max(viewL, tab.left());
                    gapR = Math.min(viewR, tab.left() + tab.measuredWidth());
                    if (gapR <= gapL) { gapL = -1; gapR = -1; } // ACTIVE TAB FULLY SCROLLED OUT OF VIEW
                    break;
                }
            }
            if (gapL < 0) {
                canvas.fill(this.left, y, this.measuredWidth, 1, AppTheme.STROKE_BRIGHT);
            } else {
                canvas.fill(this.left, y, Math.max(0, gapL - this.left), 1, AppTheme.STROKE_BRIGHT);
                canvas.fill(gapR, y, Math.max(0, this.left + this.measuredWidth - gapR), 1, AppTheme.STROKE_BRIGHT);
            }
        }
    }

    // ONE SPEC FOLDER TAB — OPEN-BOTTOM FRAME WITH THE SPEC ICON, TITLE + SUBTITLE AND THE SPEC'S SAVE
    // PIP; THE ACTIVE TAB LIGHTS ITS ACCENT AND BREAKS THE STRIP BASELINE (SEE TabStrip) SO IT CONNECTS
    // TO THE SETTINGS BELOW
    private final class SpecTab extends Element<SpecTab> {

        private static final int ICON = 16; // ICON EDGE IN LOGICAL PX, SAME AS THE TITLEBAR
        private static final int PIP = 8;   // SAVE-STATUS SQUARE EDGE, SAME AS StatusSquare

        private final int index;
        private final String title;
        private final String subtitle;
        // PIXEL-ICON GLYPH NAME, OR null TO DRAW THE APP TEXTURE ICON (WATERMEDIA'S OWN SPECS)
        private final String iconName;

        private SpecTab(final int index, final String title, final String subtitle, final String iconName) {
            this.index = index;
            this.title = title.toUpperCase(Locale.ROOT);
            this.subtitle = subtitle.toUpperCase(Locale.ROOT);
            this.iconName = iconName;
            this.height = MAX_PARENT;
        }

        @Override
        protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
            final TextRenderer text = SettingsScreen.this.text;
            // 16 PAD + ICON + 8 GAP | TEXT BLOCK | 12 GAP + PIP + 16 PAD
            this.contentWidth = Math.max(text.widthBold(this.title, AppTheme.TEXT_BUTTON),
                    text.width(this.subtitle, AppTheme.TEXT_SUBTITLE)) + 76;
            this.contentHeight = innerAvailHeight;
        }

        @Override
        protected void onDraw(final Canvas canvas) {
            final boolean active = this.index == SettingsScreen.this.activeSpecIndex;
            final Color accent = TAB_ACCENTS[this.index % TAB_ACCENTS.length];
            final int x = this.left;
            final int y = this.top;
            final int w = this.measuredWidth;
            final int h = this.measuredHeight;
            if (active) {
                canvas.fill(x, y, w, h, AppTheme.alpha(AppTheme.BG_2, 235));
                canvas.glow(x, y, w, h, 0f, accent, 0.18f);
            } else {
                canvas.fill(x, y, w, h, AppTheme.alpha(AppTheme.BG_1, this.hovered ? 205 : 140));
            }
            // OPEN-BOTTOM FRAME — TOP AND SIDE EDGES ONLY; THE STRIP BASELINE CLOSES INACTIVE TABS
            final Color edge = active ? accent : this.hovered ? AppTheme.STROKE_BRIGHT : AppTheme.STROKE;
            final float edgeW = active ? 1.5f : 1f;
            canvas.lineH(x, y, w, edge, edgeW);
            canvas.line(x, y, x, y + h, edge, edgeW);
            canvas.line(x + w, y, x + w, y + h, edge, edgeW);
            final int titleH = canvas.textHeight(AppTheme.TEXT_BUTTON, true);
            final int subH = canvas.textHeight(AppTheme.TEXT_SUBTITLE, false);
            final int blockH = titleH + 2 + subH;
            final int textY = y + Math.max(0, (h - blockH) / 2);
            // TAB ICON — WATERMEDIA'S OWN SPECS (SHELL + INSTANCE) SHOW THE APP TEXTURE (READ PER DRAW AS
            // IT CHANGES ACROSS ENGINE SWAPS/ASSET RELOADS); OTHER SPECS SHOW A GLYPH TINTED LIKE THE
            // TITLE. THE ICON IS CENTERED VERTICALLY ON THE TITLE+SUBTITLE BLOCK; THE TEXT KEEPS ITS SLOT
            final int iconY = textY + (blockH - ICON) / 2;
            if (this.iconName == null) {
                final int iconId = SettingsScreen.this.ctx.assets.iconId;
                if (iconId > 0) canvas.image(iconId, x + 16, iconY, ICON, ICON, null);
            } else {
                canvas.icon(this.iconName, x + 16, iconY, ICON,
                        active ? accent : this.hovered ? AppTheme.TEXT : AppTheme.TEXT_SOFT);
            }
            final int textX = x + 16 + ICON + 8;
            canvas.text(this.title, textX, textY,
                    active ? accent : this.hovered ? AppTheme.TEXT : AppTheme.TEXT_SOFT, AppTheme.TEXT_BUTTON, true);
            canvas.text(this.subtitle, textX, textY + titleH + 2, AppTheme.TEXT_FAINT, AppTheme.TEXT_SUBTITLE, false);
            // SAVE PIP — THE SAME 8x8 SQUARE StatusSquare DRAWS, FED BY THIS SPEC'S OWN SaveMachine
            // WITH THE OLD CONFIG CHIP'S STATE MAP (SAVING PULSES, RESAVE/FIRST FAILURE WARNS,
            // SECOND FAILURE ERRORS, IDLE/SAVED IS OK)
            final StatusSquare.Status status = switch (SettingsScreen.this.specs.get(this.index).save.state) {
                case FAILED_TWICE -> StatusSquare.Status.ERROR;
                case FAILED_ONCE, RESAVING -> StatusSquare.Status.WARN;
                case SAVING -> StatusSquare.Status.PENDING;
                default -> StatusSquare.Status.OK; // READY / SAVED
            };
            final Color pip = StatusSquare.tint(status);
            final int pipX = x + w - 16 - PIP;
            final int pipY = y + (h - PIP) / 2;
            canvas.fill(pipX, pipY, PIP, PIP, pip);
            canvas.glow(pipX, pipY, PIP, PIP, 0f, pip, 0.42f);
        }
    }

    // SECTION ENTRY — CLEAN MRL-STYLE TREE ROW: NESTED GROUPS INDENT 14px PER LEVEL AND THE ACTIVE NODE
    // LIGHTS THE AMBER BAR + FILL (COHERENT WITH THE AMBER CONTROL THUMBS); FLAT TEXT OTHERWISE
    private final class SectionItem extends Element<SectionItem> {

        private static final int INDENT = 14;

        private final int index;
        private final String name;
        private final int indent;

        private SectionItem(final int index, final SettingSection section) {
            this.index = index;
            this.name = section.name.toUpperCase(Locale.ROOT);
            this.indent = section.depth * INDENT;
            this.width = MAX_PARENT;
            this.height = SECTION_H;
        }

        @Override
        protected void onDraw(final Canvas canvas) {
            final boolean active = this.index == SettingsScreen.this.activeSectionIndex;
            final int x = this.left + this.indent;
            final int y = this.top;
            final int w = this.measuredWidth - this.indent;
            final int h = this.measuredHeight;
            if (active) {
                canvas.fill(x, y, w, h, AppTheme.alpha(AppTheme.AMBER, 30));
                canvas.fill(x, y + 4, 3, h - 8, AppTheme.AMBER);
                canvas.glow(x, y, w, h, 0f, AppTheme.AMBER, 0.14f);
            } else if (this.hovered) {
                canvas.fill(x, y, w, h, AppTheme.alpha(AppTheme.AMBER, 18));
            }
            canvas.text(canvas.text().truncateToWidth(this.name, w - 26, AppTheme.TEXT_BODY, Font.BOLD),
                    x + 14, y + Math.max(0, (h - canvas.textHeight(AppTheme.TEXT_BODY, true)) / 2),
                    active ? AppTheme.AMBER : this.hovered ? AppTheme.TEXT : AppTheme.TEXT_SOFT,
                    AppTheme.TEXT_BODY, true);
        }
    }

    // ONE SETTING ROW — LABEL/NOTE/SELECTION SELF-DRAWN (PIXEL PORT OF THE LEGACY ROW), THE CONTROL IS A
    // REAL WIDGET RIGHT-ALIGNED AND VERTICALLY CENTERED. THE binder PULLS LIVE VALUES EVERY UPDATE PASS.
    private final class RowElement extends Group<RowElement> {

        private final Setting setting;
        private final Element<?> control;
        private final int ctlW; // PREFERRED CONTROL WIDTH IN PIXELS; 0 = WRAP TO THE WIDGET'S OWN SIZE
        private final Runnable binder;
        private Text seekValue; // SEEKBAR VALUE LABEL — SELECTION-TINTED AT DRAW TIME

        private RowElement(final Setting setting) {
            this.setting = setting;
            this.width = MAX_PARENT;
            final TextRenderer text = SettingsScreen.this.text;
            final Control kind = setting.control();
            final boolean enumKind = kind == Control.DROPDOWN || kind == Control.RADIO_BUTTON
                    || kind == Control.LIST_SPINNER || kind == Control.SEGMENTED;
            final Element<?> ctl;
            final Runnable bind;
            int prefW = 0;

            if (!setting.mutable || kind == Control.LABEL || kind == Control.LIST_EDITOR
                    || kind == Control.TAG_INPUT || kind == Control.CHECKBOX_LIST
                    || (enumKind && !setting.isEnumControl())) {
                // READ-ONLY VALUE — RIGHT-ALIGNED, STATUS-COLORED TEXT
                final Text value = new Text().scale(AppTheme.TEXT_BODY).gravity(Gravity.RIGHT).height(CONTROL_H);
                bind = () -> {
                    final String raw = setting.valueLabel();
                    value.text(raw.toUpperCase(Locale.ROOT)).color(statusColor(raw));
                };
                prefW = CONTROL_W + 120;
                ctl = value;
            } else if (kind == Control.SWITCH) {
                final Switch control = new Switch().accent(AppTheme.AMBER)
                        .onChange(v -> SettingsScreen.this.apply(() -> setting.adjust(1, 1)));
                bind = () -> control.on(setting.booleanValue());
                ctl = control;
            } else if (kind == Control.CHECKBOX) {
                final CheckBox control = new CheckBox().accent(AppTheme.GREEN)
                        .onChange(v -> SettingsScreen.this.apply(() -> setting.adjust(1, 1)));
                bind = () -> control.checked(setting.booleanValue());
                ctl = control;
            } else if (enumKind && !SettingsScreen.this.needsDropdown(setting)) {
                // COMPACT ENUM — ONE SEGMENT PER CONSTANT
                final List<String> options = setting.optionLabels();
                final String[] segments = new String[options.size()];
                int segW = 0;
                for (int i = 0; i < segments.length; i++) {
                    segments[i] = options.get(i).toUpperCase(Locale.ROOT);
                    segW += SegmentedControl.segmentWidth(text, segments[i], AppTheme.TEXT_BODY);
                }
                // INVERT THE MODEL'S ENABLED MASK INTO THE WIDGET'S DISABLED MASK (null STAYS null = ALL ENABLED)
                final boolean[] enabledMask = setting.optionEnabled();
                boolean[] disabledMask = null;
                if (enabledMask != null) {
                    disabledMask = new boolean[enabledMask.length];
                    for (int i = 0; i < enabledMask.length; i++) disabledMask[i] = !enabledMask[i];
                }
                final SegmentedControl control = new SegmentedControl().segments(segments)
                        .accent(AppTheme.CYAN).scale(AppTheme.TEXT_BODY).height(CONTROL_H)
                        .disabled(disabledMask)
                        .onSelect(i -> SettingsScreen.this.apply(() -> setting.selectOption(i)));
                bind = () -> control.selected(setting.selectedOptionIndex());
                prefW = segW;
                ctl = control;
            } else if (enumKind) {
                // WIDE ENUM — DROPDOWN THAT OPENS A FLOATING MENU ANCHORED TO THE CONTROL (LEGACY UX). ITS
                // POPUP MOUNTS INTO THE SCREEN OVERLAY SO IT STACKS ON TOP AND IS TORN DOWN ON SECTION SWITCH
                final List<String> options = setting.optionLabels();
                final List<String> labels = new ArrayList<>(options.size());
                for (final String option: options) labels.add(option.toUpperCase(Locale.ROOT));
                final Dropdown control = new Dropdown().mode(Dropdown.Mode.ATTACH)
                        .accent(AppTheme.CYAN).scale(AppTheme.TEXT_BODY).height(CONTROL_H)
                        .items(labels)
                        .overlayHost(SettingsScreen.this.overlay())
                        .onSelect(i -> SettingsScreen.this.apply(() -> setting.selectOption(i)));
                bind = () -> control.selected(setting.selectedOptionIndex());
                // THE BOX RENDERS THE VALUE BOLD; SIZE FOR THE WIDEST LABEL PLUS THE 10px INSET, ARROW ZONE AND MARGIN
                prefW = Math.max(160, text.widthBold(setting.widestValueLabel().toUpperCase(Locale.ROOT), AppTheme.TEXT_BODY) + 46);
                ctl = control;
            } else if (setting.isSeekbarControl()) {
                // RANGED NUMBER — SEEK BAR PLUS THE LIVE VALUE LABEL (FIXED WIDTH SO THE TRACK NEVER JITTERS)
                final SeekBar bar = new SeekBar().trackHeight(6).height(16).gravity(Gravity.CENTER)
                        .onChange(f -> SettingsScreen.this.seek(setting, f));
                final Text value = new Text().scale(AppTheme.TEXT_SUBTITLE).gravity(Gravity.RIGHT)
                        .size(Math.max(58, text.width(seekDisplay(setting, setting.widestValueLabel()), AppTheme.TEXT_SUBTITLE) + 6), CONTROL_H);
                this.seekValue = value;
                bind = () -> {
                    bar.value(setting.rangePercent());
                    value.text(seekDisplay(setting, setting.valueLabel()));
                };
                prefW = CONTROL_W;
                ctl = Parent.row().spacing(14).height(CONTROL_H).add(bar).add(value);
            } else if (kind == Control.NUMBER_SPINNER || kind == Control.SEEKBAR
                    || kind == Control.RANGE_SLIDER || kind == Control.KNOB || setting.isSpinnerControl()) {
                // UNRANGED OR SPINNER NUMBER — [-] VALUE [+] WITH AN EDITABLE CENTER. THE SPINNER COMMITS THE
                // STEPPED OR TYPED VALUE THROUGH onChange; SET IT STRAIGHT ONTO THE SETTING (number() CLAMPS
                // BY THE SAME PATH AS commit/adjust) INSTEAD OF INFERRING A DIRECTION FROM THE PREVIOUS VALUE
                final String suffix = setting.suffix();
                final Spinner control = new Spinner().range(-1e18, 1e18).step(1).height(CONTROL_H)
                        .suffix(suffix.isBlank() ? "" : " " + suffix.toUpperCase(Locale.ROOT))
                        .onChange(v -> SettingsScreen.this.apply(() -> setting.number(v)));
                bind = () -> control.value(setting.number());
                final int suffixW = suffix.isBlank() ? 0 : text.width(suffix, AppTheme.TEXT_SUBTITLE) + 16;
                prefW = Math.max(190, text.width(setting.widestValueLabel(), AppTheme.TEXT_BODY) + suffixW + 110);
                ctl = control;
            } else {
                // FREE TEXT — LIVE TEXT FIELD; ENTER COMMITS, ESC CANCELS (SEE dispatchKey/dispatchClick). NO
                // EXPLICIT ACCENT SO FOCUS HIGHLIGHTS IN THE FRAMEWORK'S DEFAULT AMBER
                final TextField field = new TextField()
                        .padding(Spacing.hv(8, 4)).height(CONTROL_H).tag(setting);
                SettingsScreen.this.textFields.add(field);
                bind = () -> {
                    if (!field.focused()) field.value(setting.valueLabel());
                };
                prefW = Path.class.isAssignableFrom(setting.valueType()) ? CONTROL_W + 120 : CONTROL_W;
                final String suffix = setting.suffix();
                if (suffix.isBlank()) {
                    ctl = field;
                } else {
                    ctl = Parent.row().spacing(6).height(CONTROL_H)
                            .add(field)
                            .add(new Text(suffix.toUpperCase(Locale.ROOT)).scale(AppTheme.TEXT_SUBTITLE)
                                    .color(AppTheme.TEXT_FAINT).height(CONTROL_H));
                }
            }

            this.control = ctl;
            this.ctlW = prefW;
            this.binder = bind;
            this.add(ctl);
        }

        @Override
        protected void onUpdate() {
            this.binder.run();
        }

        @Override
        protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
            // CLAMP FIXED-WIDTH CONTROLS TO THE LEGACY BUDGET SO THE LABEL ALWAYS KEEPS ITS MINIMUM SPACE
            if (this.ctlW > 0) this.control.width(Math.min(this.ctlW, Math.max(120, innerAvailWidth - 160)));
            this.control.measure(innerAvailWidth, innerAvailHeight);
            this.contentWidth = innerAvailWidth;
            this.contentHeight = innerAvailHeight;
        }

        @Override
        protected void onLayout() {
            this.control.layout(this.left + this.measuredWidth - this.control.measuredWidth() - 14,
                    this.top + Math.max(0, (this.measuredHeight - this.control.measuredHeight()) / 2));
        }

        @Override
        protected void onDraw(final Canvas canvas) {
            final Color accent = this.setting.accent();
            final int w = this.measuredWidth;
            final int h = this.measuredHeight;
            if (this.selected) {
                canvas.fill(this.left, this.top, w, h, AppTheme.alpha(AppTheme.NEON_DARK, 36));
                canvas.fill(this.left, this.top + 6, 3, h - 12, accent);
                canvas.glow(this.left, this.top + 4, w, h - 8, 0f, accent, 0.14f);
            }
            // DOTTED BOTTOM DIVIDER — 4px DASHES EVERY 8px, SAME AS THE LEGACY ROW
            for (int dx = this.left; dx < this.left + w; dx += 8) {
                canvas.fill(dx, this.top + h - 1, 4, 1, AppTheme.alpha(AppTheme.STROKE_BRIGHT, 76));
            }
            // OPTION NAME — VERBATIM (NO UPPERCASING) AT SECTION SCALE FOR CLEAR HIERARCHY, ALWAYS
            // VERTICALLY CENTERED NOW THAT THE DESCRIPTION LIVES IN THE CURSOR TOOLTIP INSTEAD
            final int labelMaxW = Math.max(80, this.control.left() - this.left - 30);
            final int labelY = this.top + Math.max(0, (h - canvas.textHeight(AppTheme.TEXT_SECTION, true)) / 2);
            canvas.text(canvas.text().truncateToWidth(this.setting.label, labelMaxW, AppTheme.TEXT_SECTION, Font.BOLD),
                    this.left + 18, labelY, this.selected ? AppTheme.NEON_LIGHT : AppTheme.TEXT_SOFT, AppTheme.TEXT_SECTION, true);
            // PUBLISH THE HOVERED ROW'S DESCRIPTION FOR THE SCREEN'S CURSOR TOOLTIP (DRAWN ABOVE THE TREE)
            if (this.hovered && !this.setting.note.isBlank()) {
                SettingsScreen.this.hoverNote = this.setting.note;
            }
            // SELECTION TINT FOR THE SEEKBAR VALUE — SET AT DRAW TIME, AFTER THE LIST STAMPED selected
            if (this.seekValue != null) {
                this.seekValue.color(this.selected ? AppTheme.NEON_LIGHT : AppTheme.TEXT_SOFT);
            }
            super.onDraw(canvas);
        }
    }

    // ==========================================================================
    // SETTINGS MODEL (UNCHANGED LOGIC — percent()/number() ARE THE ONLY SKIN ADAPTERS)
    // ==========================================================================

    private enum SaveState {
        READY, SAVING, SAVED, FAILED_ONCE, RESAVING, FAILED_TWICE
    }

    // ONE SPEC'S AUTOSAVE STATE — saving GATES CONCURRENT WRITERS AGAINST THE SAME FILE; resavePending
    // COALESCES A CHANGE THAT ARRIVES MID-SAVE INTO ONE FOLLOW-UP SAVE. ALL FIELDS ARE RENDER-THREAD
    // CONFINED: THE WORKER ONLY WRITES THE DISK, ITS COMPLETION IS PUBLISHED BACK VIA ctx.execute
    // (SEE startSave/finishSave), SO NO volatile IS NEEDED.
    private static final class SaveMachine {
        private SaveState state = SaveState.READY;
        private int failures;
        private boolean saving;
        private boolean resavePending;
    }

    // A CACHED REVERSE-SPEC PARSE TIED TO ITS SOURCE FILE'S mtime (-1 = ABSENT); spec IS null WHEN THE FILE
    // WAS ABSENT/UNPARSEABLE AT THAT mtime, CACHING THE "SKIP THIS TAB" DECISION TOO
    private record ReverseSpec(long mtime, ConfigSpec spec) {
    }

    private static final class SettingSpec {
        private final String title;
        private final String subtitle;
        private final boolean persistent;
        private final ConfigSpec configSpec;
        // RUNS AFTER EVERY MUTATION OF THIS SPEC — THE SHELL SPEC PUSHES ITS VALUES INTO THE LIVE APP
        // STATE HERE (AppConfig.apply) SO EDITS TAKE EFFECT IMMEDIATELY; null WHEN NOTHING TO APPLY
        private final Runnable applier;
        // THE SCREEN-OWNED SAVE MACHINE OF THIS SPEC SLOT — SHARED ACROSS REBUILDS (SEE saves)
        private final SaveMachine save;
        // PIXEL-ICON NAME FOR THE TAB, OR null TO DRAW THE APP TEXTURE ICON (SHELL + INSTANCE ARE
        // WATERMEDIA'S OWN, SO THEY KEEP THE APP LOGO; OTHER SPECS CARRY A GLYPH)
        private final String iconName;
        // FLAT DFS-ORDERED SECTION TREE — depth EXPRESSES NESTING, PGUP/PGDN WALKS THIS LIST IN ORDER
        private final List<SettingSection> sections = new ArrayList<>();

        private SettingSpec(final String title, final String subtitle, final boolean persistent,
                            final ConfigSpec configSpec, final Runnable applier, final SaveMachine save,
                            final String iconName) {
            this.title = title;
            this.subtitle = subtitle;
            this.persistent = persistent;
            this.configSpec = configSpec;
            this.applier = applier;
            this.save = save;
            this.iconName = iconName;
        }
    }

    private static final class SettingSection {
        private final String name;
        private final String detail;
        // HEADER SUBTITLE PATH ("audio" OR "group1 / group2"); THE HOME NODE CARRIES THE SPEC NAME
        private final String path;
        // TREE NESTING LEVEL (0 = TOP) — THE SECTION COLUMN INDENTS BY THIS
        private final int depth;
        private final List<Setting> settings = new ArrayList<>();

        private SettingSection(final String name, final String detail, final String path, final int depth) {
            this.name = name;
            this.detail = detail;
            this.path = path;
            this.depth = depth;
        }
    }

    private abstract static class Setting {
        protected final String label;
        protected final String key;
        protected final String type;
        protected final String note;
        protected final boolean mutable;

        protected Setting(final String label, final String key, final String type, final String note, final boolean mutable) {
            this.label = label;
            this.key = key;
            this.type = type;
            // BLANK STAYS BLANK — A SETTING WITHOUT A DESCRIPTION SIMPLY SHOWS NO TOOLTIP
            this.note = note == null || note.isBlank() ? "" : note.trim();
            this.mutable = mutable;
        }

        abstract String valueLabel();

        String editValue() {
            return this.valueLabel();
        }

        boolean editableText() {
            return false;
        }

        boolean isEnumControl() {
            return false;
        }

        boolean isSeekbarControl() {
            return false;
        }

        boolean isSpinnerControl() {
            return false;
        }

        boolean booleanValue() {
            return false;
        }

        Class<?> valueType() {
            return Object.class;
        }

        String widestValueLabel() {
            return this.valueLabel();
        }

        List<String> optionLabels() {
            return List.of();
        }

        // PER-OPTION AVAILABILITY FOR ENUM CONTROLS (null = ALL ENABLED). A DISABLED OPTION RENDERS DIMMED
        // AND CANNOT BE SELECTED — E.G. VULKAN WHEN NO VULKAN LOADER IS PRESENT ON THIS MACHINE.
        boolean[] optionEnabled() {
            return null;
        }

        int selectedOptionIndex() {
            return -1;
        }

        boolean selectOption(final int index) {
            return false;
        }

        Control control() {
            return Control.LABEL;
        }

        String suffix() {
            return "";
        }

        float rangePercent() {
            return 0f;
        }

        // SETS A RANGED NUMBER FROM A 0..1 FRACTION (SEEK BAR SKIN ADAPTER)
        boolean percent(final double pct) {
            return false;
        }

        // CURRENT NUMERIC VALUE FOR THE SPINNER SKIN; 0 WHEN THE SETTING IS NOT A NUMBER
        double number() {
            return 0d;
        }

        // SETS A NUMBER DIRECTLY FROM THE SPINNER (STEPPED OR TYPED), CLAMPED THROUGH THE SAME PATH AS
        // commit/adjust; RETURNS WHETHER THE STORED VALUE ACTUALLY CHANGED
        boolean number(final double value) {
            return false;
        }

        boolean adjust(final int direction, final int step) {
            return false;
        }

        void commit(final String value) {
        }

        void reset() {
        }

        Color accent() {
            return this.mutable ? AppTheme.NEON_LIGHT : AppTheme.TEXT_FAINT;
        }
    }

    private static final class ReadOnlySetting extends Setting {
        private final Supplier<String> value;

        private ReadOnlySetting(final String label, final String key, final String type,
                                final String note, final Supplier<String> value) {
            super(label, key, type, note, false);
            this.value = value;
        }

        @Override
        String valueLabel() {
            final String current = this.value.get();
            return current == null || current.isBlank() ? "EMPTY" : current;
        }
    }

    // RUNTIME ENUM SELECTOR (LIST_SPINNER) BACKED BY A getter/setter — RESERVED FOR THE STRICTLY
    // NON-PORTABLE SHELL SETTINGS (ENGINE/UI SCALE, WHOSE .cfg STORES PREDATE ANY CONFIG SPEC).
    // CYCLES THROUGH THE CONSTANTS WITH LEFT/RIGHT OR SELECTS DIRECTLY FROM THE WIDGET.
    private static final class RuntimeEnumSetting<E extends Enum<E>> extends Setting {
        private final E[] options;
        private final Supplier<E> getter;
        private final Consumer<E> setter;
        private final E defaultValue;
        // MAPS A CONSTANT TO ITS DISPLAY LABEL — DEFAULTS TO name(), OVERRIDDEN FOR ENUMS WHOSE
        // LABELS ARE NOT VALID IDENTIFIERS (E.G. UI SCALE "1.0x"/"1.25x")
        private final Function<E, String> labeler;
        // OPTIONAL PER-CONSTANT AVAILABILITY GATE (null = ALL ENABLED) — DISABLED CONSTANTS ARE DIMMED AND SKIPPED
        private final Predicate<E> enabled;

        private RuntimeEnumSetting(final String label, final String key, final String type, final String note,
                                   final E[] options, final Supplier<E> getter, final Consumer<E> setter,
                                   final E defaultValue, final Function<E, String> labeler) {
            this(label, key, type, note, options, getter, setter, defaultValue, labeler, null);
        }

        private RuntimeEnumSetting(final String label, final String key, final String type, final String note,
                                   final E[] options, final Supplier<E> getter, final Consumer<E> setter,
                                   final E defaultValue, final Function<E, String> labeler, final Predicate<E> enabled) {
            super(label, key, type, note, true);
            this.options = options;
            this.getter = getter;
            this.setter = setter;
            this.defaultValue = defaultValue;
            this.labeler = labeler;
            this.enabled = enabled;
        }

        private boolean enabled(final E option) {
            return this.enabled == null || this.enabled.test(option);
        }

        @Override
        String valueLabel() {
            return this.labeler.apply(this.getter.get());
        }

        @Override
        boolean isEnumControl() {
            return true;
        }

        @Override
        Control control() {
            return Control.LIST_SPINNER;
        }

        @Override
        List<String> optionLabels() {
            final List<String> labels = new ArrayList<>(this.options.length);
            for (final E option: this.options) labels.add(this.labeler.apply(option));
            return labels;
        }

        @Override
        int selectedOptionIndex() {
            final E current = this.getter.get();
            for (int i = 0; i < this.options.length; i++) if (this.options[i] == current) return i;
            return 0;
        }

        @Override
        boolean[] optionEnabled() {
            if (this.enabled == null) return null;
            final boolean[] mask = new boolean[this.options.length];
            for (int i = 0; i < this.options.length; i++) mask[i] = this.enabled(this.options[i]);
            return mask;
        }

        @Override
        boolean selectOption(final int index) {
            if (index < 0 || index >= this.options.length || !this.enabled(this.options[index])) return false;
            this.setter.accept(this.options[index]);
            return true;
        }

        @Override
        String widestValueLabel() {
            String widest = this.valueLabel();
            for (final E option: this.options) {
                final String label = this.labeler.apply(option);
                if (label.length() > widest.length()) widest = label;
            }
            return widest;
        }

        @Override
        boolean adjust(final int direction, final int step) {
            // CYCLE PAST DISABLED CONSTANTS SO KEYBOARD LEFT/RIGHT NEVER LANDS ON AN UNAVAILABLE OPTION
            final int n = this.options.length;
            int next = this.selectedOptionIndex();
            for (int i = 0; i < n; i++) {
                next = Math.floorMod(next + direction, n);
                if (this.enabled(this.options[next])) {
                    this.setter.accept(this.options[next]);
                    return true;
                }
            }
            return false;
        }

        @Override
        void reset() {
            if (this.enabled(this.defaultValue)) this.setter.accept(this.defaultValue);
        }

        @Override
        Color accent() {
            return AppTheme.CYAN;
        }
    }

    private static final class ConfigSetting extends Setting {
        private final IConfigField<?, ?> field;
        private final Class<?> valueType;
        private final Control control;
        private final String suffix;
        private final Double minValue;
        private final Double maxValue;

        private ConfigSetting(final IConfigField<?, ?> field, final String key) {
            super(titleCase(field.name()), key, typeName(field), joinedComments(field), fieldMutable(field));
            this.field = field;
            this.valueType = field.type();
            this.control = safeControl(field);
            final String rawSuffix = field.suffix();
            this.suffix = rawSuffix == null || rawSuffix.isBlank() ? "" : rawSuffix.trim();
            this.minValue = numberLimit(field, "minValueString");
            this.maxValue = numberLimit(field, "maxValueString");
        }

        @Override
        String valueLabel() {
            final Object value = this.field.get();
            if (value == null) return "EMPTY";
            if (value instanceof Boolean bool) return bool ? "ON" : "OFF";
            if (value instanceof Path path) {
                final String raw = path.toString();
                return raw.isBlank() ? "." : raw;
            }
            if (this.control == Control.PASSWORD) {
                final int len = Math.max(8, String.valueOf(value).length());
                return "*".repeat(Math.min(32, len));
            }
            return String.valueOf(value);
        }

        @Override
        String editValue() {
            final Object value = this.field.get();
            return value == null ? "" : String.valueOf(value);
        }

        @Override
        boolean editableText() {
            return this.mutable && switch (this.control) {
                case INPUT, TEXT_AREA, INPUT_PASTE, PASSWORD, INPUT_FILE, INPUT_FOLDER, COLOR_PICKER, KEYBIND -> true;
                default -> false;
            };
        }

        @Override
        boolean isEnumControl() {
            return this.valueType.isEnum()
                    && (this.control == Control.DROPDOWN || this.control == Control.RADIO_BUTTON
                    || this.control == Control.LIST_SPINNER || this.control == Control.SEGMENTED);
        }

        @Override
        boolean isSeekbarControl() {
            return this.isNumber() && this.hasUsableRange()
                    && (this.control == Control.SEEKBAR || this.control == Control.RANGE_SLIDER || this.control == Control.KNOB);
        }

        @Override
        boolean isSpinnerControl() {
            return this.isNumber() && (this.control == Control.NUMBER_SPINNER || !this.hasUsableRange());
        }

        @Override
        boolean booleanValue() {
            return Boolean.TRUE.equals(this.field.get());
        }

        @Override
        Class<?> valueType() {
            return this.valueType;
        }

        @Override
        String widestValueLabel() {
            String widest = this.valueLabel();
            if (this.valueType.isEnum()) {
                final Object[] constants = this.valueType.getEnumConstants();
                if (constants != null) {
                    for (final Object constant: constants) {
                        widest = wider(widest, String.valueOf(constant));
                    }
                }
                return widest;
            }
            if (this.isNumber()) {
                if (this.minValue != null) widest = wider(widest, String.valueOf(this.numberOf(this.minValue)));
                if (this.maxValue != null) widest = wider(widest, String.valueOf(this.numberOf(this.maxValue)));
            }
            return widest;
        }

        @Override
        List<String> optionLabels() {
            final Object[] constants = this.valueType.isEnum() ? this.valueType.getEnumConstants() : null;
            if (constants == null || constants.length == 0) return List.of();
            final List<String> labels = new ArrayList<>(constants.length);
            for (final Object constant: constants) {
                labels.add(String.valueOf(constant));
            }
            return labels;
        }

        @Override
        int selectedOptionIndex() {
            if (!this.valueType.isEnum()) return -1;
            final Object[] constants = this.valueType.getEnumConstants();
            if (constants == null || constants.length == 0) return -1;
            final Object current = this.field.get();
            for (int i = 0; i < constants.length; i++) {
                if (constants[i] == current) return i;
            }
            return 0;
        }

        @Override
        boolean selectOption(final int index) {
            if (!this.valueType.isEnum()) return false;
            final Object[] constants = this.valueType.getEnumConstants();
            if (constants == null || index < 0 || index >= constants.length) return false;
            this.setValue(constants[index]);
            return true;
        }

        @Override
        Control control() {
            return this.control;
        }

        @Override
        String suffix() {
            return this.suffix;
        }

        @Override
        float rangePercent() {
            if (!this.hasUsableRange()) return 0f;
            final double value = ((Number) this.field.get()).doubleValue();
            final double min = this.minValue;
            final double max = this.maxValue;
            // hasUsableRange() ALREADY GUARANTEES MAX > MIN, SO THE SPAN IS SAFE TO DIVIDE BY DIRECTLY
            return (float) Math.max(0d, Math.min(1d, (value - min) / (max - min)));
        }

        @Override
        boolean percent(final double pct) {
            if (!this.isSeekbarControl()) return false;
            // ONLY REPORT A CHANGE (AND ARM THE DRAG-SAVE / CHIME) WHEN THE STORED VALUE ACTUALLY MOVES,
            // MIRRORING number() — A SEEK TICK LANDING ON THE SAME VALUE MUST NOT TRIGGER A POINTLESS SAVE
            final Object next = this.valueForPercent(pct);
            if (((Number) next).doubleValue() == ((Number) this.field.get()).doubleValue()) return false;
            this.setValue(next);
            return true;
        }

        @Override
        double number() {
            return this.isNumber() ? ((Number) this.field.get()).doubleValue() : 0d;
        }

        @Override
        boolean number(final double value) {
            if (!this.isNumber()) return false;
            // CLAMP THROUGH clampNumber (THE SAME [MIN, MAX] PATH THE H-04/M-05 CLAMPS USE) AND ONLY WRITE
            // WHEN THE STORED VALUE ACTUALLY MOVES, SO A NO-OP EDIT NEITHER SAVES NOR CHIMES
            final Object next = this.clampNumber(value);
            if (((Number) next).doubleValue() == ((Number) this.field.get()).doubleValue()) return false;
            this.setValue(next);
            return true;
        }

        @Override
        boolean adjust(final int direction, final int step) {
            if (this.isBoolean()) {
                this.setValue(!Boolean.TRUE.equals(this.field.get()));
                return true;
            }
            if (this.valueType.isEnum()) {
                final Object[] constants = this.valueType.getEnumConstants();
                if (constants == null || constants.length == 0) return false;
                final Object current = this.field.get();
                int currentIndex = 0;
                for (int i = 0; i < constants.length; i++) {
                    if (constants[i] == current) {
                        currentIndex = i;
                        break;
                    }
                }
                final int next = Math.floorMod(currentIndex + direction, constants.length);
                this.setValue(constants[next]);
                return true;
            }
            if (this.isInteger()) {
                final Number current = (Number) this.field.get();
                final Object next = parseNumber(String.valueOf(current.longValue() + (long) direction * Math.max(1, step)), this.valueType);
                this.setValue(this.clampNumber(next));
                return true;
            }
            if (this.isNumber()) {
                final Number current = (Number) this.field.get();
                // FRACTIONAL FIELD: DERIVE THE PER-PRESS DELTA FROM THE SPAN WITHOUT FLOORING IT TO 1.0
                // SO INTERMEDIATE FLOAT VALUES STAY REACHABLE BY KEYBOARD
                final double delta = this.hasUsableRange()
                        ? (this.maxValue - this.minValue) / 100d * direction * step
                        : direction * step;
                // CLAMP TO [MIN, MAX] LIKE THE INTEGER BRANCH DOES
                this.setValue(this.clampNumber(current.doubleValue() + delta));
                return true;
            }
            return false;
        }

        @Override
        void commit(final String value) {
            this.setValue(this.parse(value));
        }

        @Override
        void reset() {
            this.field.reset();
        }

        @Override
        Color accent() {
            final Object value = this.field.get();
            if (value instanceof Boolean bool) return bool ? AppTheme.GREEN : AppTheme.TEXT_FAINT;
            if (this.valueType.isEnum()) return AppTheme.CYAN;
            if (this.isNumber()) return AppTheme.AMBER;
            return AppTheme.NEON_LIGHT;
        }

        private Object parse(final String raw) {
            final String value = raw == null ? "" : raw.trim();
            if (this.isBoolean()) {
                if ("on".equalsIgnoreCase(value) || "true".equalsIgnoreCase(value)) return Boolean.TRUE;
                if ("off".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) return Boolean.FALSE;
                throw new IllegalArgumentException("Expected on/off or true/false");
            }
            if (this.valueType.isEnum()) {
                final Object[] constants = this.valueType.getEnumConstants();
                if (constants != null) {
                    for (final Object constant: constants) {
                        if (((Enum<?>) constant).name().equalsIgnoreCase(value)) return constant;
                    }
                }
                throw new IllegalArgumentException("Unknown enum value: " + value);
            }
            if (this.isNumber()) return this.clampNumber(parseNumber(value, this.valueType));
            if (Path.class.isAssignableFrom(this.valueType)) return Path.of(value);
            if (this.valueType == Character.class || this.valueType == char.class) {
                if (value.length() != 1) throw new IllegalArgumentException("Expected one character");
                return value.charAt(0);
            }
            return raw == null ? "" : raw;
        }

        private boolean hasUsableRange() {
            if (this.minValue == null || this.maxValue == null) return false;
            if (!Double.isFinite(this.minValue) || !Double.isFinite(this.maxValue)) return false;
            if (this.maxValue <= this.minValue) return false;
            final double span = this.maxValue - this.minValue;
            if (this.valueType == Integer.class || this.valueType == int.class) return span <= 1_000_000d;
            if (this.valueType == Long.class || this.valueType == long.class) return span <= 1_000_000d;
            if (this.valueType == Short.class || this.valueType == short.class) return true;
            if (this.valueType == Byte.class || this.valueType == byte.class) return true;
            return span <= 10_000d;
        }

        private Object valueForPercent(final double pct) {
            final double raw = this.minValue + (this.maxValue - this.minValue) * Math.max(0d, Math.min(1d, pct));
            if (this.isInteger()) {
                return this.numberOf(Math.round(raw));
            }
            return this.numberOf(raw);
        }

        private Object clampNumber(final Object value) {
            if (!(value instanceof Number number)) return value;
            double next = number.doubleValue();
            if (this.minValue != null) next = Math.max(next, this.minValue);
            if (this.maxValue != null) next = Math.min(next, this.maxValue);
            return this.numberOf(next);
        }

        private Object numberOf(final double value) {
            if (this.valueType == Integer.class || this.valueType == int.class) return (int) Math.round(value);
            if (this.valueType == Long.class || this.valueType == long.class) return Math.round(value);
            if (this.valueType == Short.class || this.valueType == short.class) return (short) Math.round(value);
            if (this.valueType == Byte.class || this.valueType == byte.class) return (byte) Math.round(value);
            if (this.valueType == Float.class || this.valueType == float.class) return (float) value;
            if (this.valueType == Double.class || this.valueType == double.class) return value;
            throw new IllegalArgumentException("Unsupported number type: " + this.valueType.getSimpleName());
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private void setValue(final Object next) {
            final Object previous = this.field.get();
            try {
                ((IConfigField) this.field).set0(next);
            } catch (final RuntimeException e) {
                try {
                    ((IConfigField) this.field).set0(previous);
                } catch (final RuntimeException ignored) {
                }
                throw e;
            }
        }

        private boolean isBoolean() {
            return this.valueType == Boolean.class || this.valueType == boolean.class;
        }

        private boolean isInteger() {
            return this.valueType == Integer.class || this.valueType == int.class
                    || this.valueType == Long.class || this.valueType == long.class
                    || this.valueType == Short.class || this.valueType == short.class
                    || this.valueType == Byte.class || this.valueType == byte.class;
        }

        private boolean isNumber() {
            return this.isInteger()
                    || this.valueType == Float.class || this.valueType == float.class
                    || this.valueType == Double.class || this.valueType == double.class;
        }

        private static boolean fieldMutable(final IConfigField<?, ?> field) {
            final Control control = safeControl(field);
            return control != Control.LABEL
                    && control != Control.LIST_EDITOR
                    && control != Control.TAG_INPUT
                    && control != Control.CHECKBOX_LIST;
        }

        private static Control safeControl(final IConfigField<?, ?> field) {
            final Control control = field.control();
            return control == null || control == Control.DEFAULT ? Control.INPUT : control;
        }

        private static String wider(final String current, final String candidate) {
            return candidate != null && candidate.length() > current.length() ? candidate : current;
        }

        private static Object parseNumber(final String raw, final Class<?> type) {
            if (type == Integer.class || type == int.class) return Integer.parseInt(raw);
            if (type == Long.class || type == long.class) return Long.parseLong(raw);
            if (type == Short.class || type == short.class) return Short.parseShort(raw);
            if (type == Byte.class || type == byte.class) return Byte.parseByte(raw);
            if (type == Float.class || type == float.class) return Float.parseFloat(raw);
            if (type == Double.class || type == double.class) return Double.parseDouble(raw);
            throw new IllegalArgumentException("Unsupported number type: " + type.getSimpleName());
        }

        private static Double numberLimit(final IConfigField<?, ?> field, final String methodName) {
            try {
                final Method method = field.getClass().getMethod(methodName);
                final Object value = method.invoke(field);
                if (value == null) return null;
                return Double.parseDouble(String.valueOf(value));
            } catch (final ReflectiveOperationException | NumberFormatException e) {
                return null;
            }
        }

        private static String typeName(final IConfigField<?, ?> field) {
            final Class<?> type = field.type();
            if (type == Boolean.class || type == boolean.class) return "BOOL";
            if (type == Integer.class || type == int.class) return "INT";
            if (type == Long.class || type == long.class) return "LONG";
            if (type == Short.class || type == short.class) return "SHORT";
            if (type == Byte.class || type == byte.class) return "BYTE";
            if (type == Float.class || type == float.class) return "FLOAT";
            if (type == Double.class || type == double.class) return "DOUBLE";
            if (type.isEnum()) return "ENUM";
            if (Path.class.isAssignableFrom(type)) return "PATH";
            if (type == String.class) return "TEXT";
            return type.getSimpleName().toUpperCase(Locale.ROOT);
        }

        // JOINED FIELD COMMENTS FOR THE ROW TOOLTIP; EMPTY WHEN THE FIELD HAS NONE (NO TOOLTIP THEN)
        private static String joinedComments(final IConfigField<?, ?> field) {
            final String[] comments = field.comments();
            if (comments == null || comments.length == 0) return "";
            final StringBuilder out = new StringBuilder();
            for (final String comment: comments) {
                if (comment == null || comment.isBlank()) continue;
                if (out.length() > 0) out.append(" ");
                out.append(comment.trim());
                if (out.length() > 180) break;
            }
            return out.toString();
        }
    }
}
