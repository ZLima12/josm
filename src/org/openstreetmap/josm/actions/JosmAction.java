// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.actions;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.GridBagLayout;
import java.awt.event.KeyEvent;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import javax.swing.AbstractAction;
import javax.swing.JOptionPane;
import javax.swing.JPanel;

import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.data.osm.DataSelectionListener;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.OsmUtils;
import org.openstreetmap.josm.data.osm.event.SelectionEventManager;
import org.openstreetmap.josm.gui.ConditionalOptionPaneUtil;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.Notification;
import org.openstreetmap.josm.gui.help.HelpUtil;
import org.openstreetmap.josm.gui.layer.LayerManager.LayerAddEvent;
import org.openstreetmap.josm.gui.layer.LayerManager.LayerChangeListener;
import org.openstreetmap.josm.gui.layer.LayerManager.LayerOrderChangeEvent;
import org.openstreetmap.josm.gui.layer.LayerManager.LayerRemoveEvent;
import org.openstreetmap.josm.gui.layer.MainLayerManager;
import org.openstreetmap.josm.gui.layer.MainLayerManager.ActiveLayerChangeEvent;
import org.openstreetmap.josm.gui.layer.MainLayerManager.ActiveLayerChangeListener;
import org.openstreetmap.josm.gui.progress.swing.PleaseWaitProgressMonitor;
import org.openstreetmap.josm.gui.widgets.JMultilineLabel;
import org.openstreetmap.josm.tools.Destroyable;
import org.openstreetmap.josm.tools.ImageProvider;
import org.openstreetmap.josm.tools.ImageResource;
import org.openstreetmap.josm.tools.Logging;
import org.openstreetmap.josm.tools.Shortcut;

/**
 * Base class helper for all Actions in JOSM. Just to make the life easier.
 *
 * This action allows you to set up an icon, a tooltip text, a globally registered shortcut, register it in the main toolbar and set up
 * layer/selection listeners that call {@link #updateEnabledState()} whenever the global context is changed.
 *
 * A JosmAction can register a {@link LayerChangeListener} and a {@link DataSelectionListener}. Upon
 * a layer change event or a selection change event it invokes {@link #updateEnabledState()}.
 * Subclasses can override {@link #updateEnabledState()} in order to update the {@link #isEnabled()}-state
 * of a JosmAction depending on the {@link #getLayerManager()} state.
 *
 * destroy() from interface Destroyable is called e.g. for MapModes, when the last layer has
 * been removed and so the mapframe will be destroyed. For other JosmActions, destroy() may never
 * be called (currently).
 *
 * @author imi
 */
public abstract class JosmAction extends AbstractAction implements Destroyable {

    protected transient Shortcut sc;
    private transient LayerChangeAdapter layerChangeAdapter;
    private transient ActiveLayerChangeAdapter activeLayerChangeAdapter;
    private transient SelectionChangeAdapter selectionChangeAdapter;

    /**
     * Constructs a {@code JosmAction}.
     *
     * @param name the action's text as displayed on the menu (if it is added to a menu)
     * @param icon the icon to use
     * @param tooltip  a longer description of the action that will be displayed in the tooltip. Please note
     *           that html is not supported for menu actions on some platforms.
     * @param shortcut a ready-created shortcut object or null if you don't want a shortcut. But you always
     *            do want a shortcut, remember you can always register it with group=none, so you
     *            won't be assigned a shortcut unless the user configures one. If you pass null here,
     *            the user CANNOT configure a shortcut for your action.
     * @param registerInToolbar register this action for the toolbar preferences?
     * @param toolbarId identifier for the toolbar preferences
     * @param installAdapters false, if you don't want to install layer changed and selection changed adapters
     */
    protected JosmAction(String name, ImageProvider icon, String tooltip, Shortcut shortcut, boolean registerInToolbar,
            String toolbarId, boolean installAdapters) {
        super(name);
        if (icon != null) {
            ImageResource resource = icon.getResource();
            if (resource != null) {
                try {
                    resource.attachImageIcon(this, true);
                } catch (RuntimeException e) {
                    Logging.warn("Unable to attach image icon {0} for action {1}", icon, name);
                    Logging.error(e);
                }
            }
        }
        setHelpId();
        sc = shortcut;
        if (sc != null && !sc.isAutomatic()) {
            MainApplication.registerActionShortcut(this, sc);
        }
        setTooltip(tooltip);
        if (getValue("toolbar") == null) {
            setToolbarId(toolbarId);
        }
        if (registerInToolbar && MainApplication.getToolbar() != null) {
            MainApplication.getToolbar().register(this);
        }
        if (installAdapters) {
            installAdapters();
        }
    }

    /**
     * The new super for all actions.
     *
     * Use this super constructor to setup your action.
     *
     * @param name the action's text as displayed on the menu (if it is added to a menu)
     * @param iconName the filename of the icon to use
     * @param tooltip  a longer description of the action that will be displayed in the tooltip. Please note
     *           that html is not supported for menu actions on some platforms.
     * @param shortcut a ready-created shortcut object or null if you don't want a shortcut. But you always
     *            do want a shortcut, remember you can always register it with group=none, so you
     *            won't be assigned a shortcut unless the user configures one. If you pass null here,
     *            the user CANNOT configure a shortcut for your action.
     * @param registerInToolbar register this action for the toolbar preferences?
     * @param toolbarId identifier for the toolbar preferences. The iconName is used, if this parameter is null
     * @param installAdapters false, if you don't want to install layer changed and selection changed adapters
     */
    protected JosmAction(String name, String iconName, String tooltip, Shortcut shortcut, boolean registerInToolbar,
            String toolbarId, boolean installAdapters) {
        this(name, iconName == null ? null : new ImageProvider(iconName).setOptional(true), tooltip, shortcut, registerInToolbar,
                toolbarId == null ? iconName : toolbarId, installAdapters);
    }

    /**
     * Constructs a new {@code JosmAction}.
     *
     * Use this super constructor to setup your action.
     *
     * @param name the action's text as displayed on the menu (if it is added to a menu)
     * @param iconName the filename of the icon to use
     * @param tooltip  a longer description of the action that will be displayed in the tooltip. Please note
     *           that html is not supported for menu actions on some platforms.
     * @param shortcut a ready-created shortcut object or null if you don't want a shortcut. But you always
     *            do want a shortcut, remember you can always register it with group=none, so you
     *            won't be assigned a shortcut unless the user configures one. If you pass null here,
     *            the user CANNOT configure a shortcut for your action.
     * @param registerInToolbar register this action for the toolbar preferences?
     * @param installAdapters false, if you don't want to install layer changed and selection changed adapters
     */
    protected JosmAction(String name, String iconName, String tooltip, Shortcut shortcut, boolean registerInToolbar, boolean installAdapters) {
        this(name, iconName, tooltip, shortcut, registerInToolbar, null, installAdapters);
    }

    /**
     * Constructs a new {@code JosmAction} and installs layer changed and selection changed adapters.
     *
     * Use this super constructor to setup your action.
     *
     * @param name the action's text as displayed on the menu (if it is added to a menu)
     * @param iconName the filename of the icon to use
     * @param tooltip  a longer description of the action that will be displayed in the tooltip. Please note
     *           that html is not supported for menu actions on some platforms.
     * @param shortcut a ready-created shortcut object or null if you don't want a shortcut. But you always
     *            do want a shortcut, remember you can always register it with group=none, so you
     *            won't be assigned a shortcut unless the user configures one. If you pass null here,
     *            the user CANNOT configure a shortcut for your action.
     * @param registerInToolbar register this action for the toolbar preferences?
     */
    protected JosmAction(String name, String iconName, String tooltip, Shortcut shortcut, boolean registerInToolbar) {
        this(name, iconName, tooltip, shortcut, registerInToolbar, null, true);
    }

    /**
     * Constructs a new {@code JosmAction}.
     */
    protected JosmAction() {
        this(true);
    }

    /**
     * Constructs a new {@code JosmAction}.
     *
     * @param installAdapters false, if you don't want to install layer changed and selection changed adapters
     */
    protected JosmAction(boolean installAdapters) {
        setHelpId();
        if (installAdapters) {
            installAdapters();
        }
    }

    /**
     * Constructs a new {@code JosmAction}.
     *
     * Use this super constructor to setup your action.
     *
     * @param name the action's text as displayed on the menu (if it is added to a menu)
     * @param iconName the filename of the icon to use
     * @param tooltip  a longer description of the action that will be displayed in the tooltip. Please note
     *           that html is not supported for menu actions on some platforms.
     * @param shortcuts ready-created shortcut objects
     * @since 14012
     */
    protected JosmAction(String name, String iconName, String tooltip, List<Shortcut> shortcuts) {
        this(name, iconName, tooltip, shortcuts.get(0), true, null, true);
        for (int i = 1; i < shortcuts.size(); i++) {
            MainApplication.registerActionShortcut(this, shortcuts.get(i));
        }
    }

    /**
     * Installs the listeners to this action.
     * <p>
     * This should either never be called or only called in the constructor of this action.
     * <p>
     * All registered adapters should be removed in {@link #destroy()}
     */
    protected void installAdapters() {
        // make this action listen to layer change and selection change events
        if (listenToLayerChange()) {
            layerChangeAdapter = buildLayerChangeAdapter();
            activeLayerChangeAdapter = buildActiveLayerChangeAdapter();
            getLayerManager().addLayerChangeListener(layerChangeAdapter);
            getLayerManager().addActiveLayerChangeListener(activeLayerChangeAdapter);
        }
        if (listenToSelectionChange()) {
            selectionChangeAdapter = new SelectionChangeAdapter();
            SelectionEventManager.getInstance().addSelectionListenerForEdt(selectionChangeAdapter);
        }
        initEnabledState();
    }

    /**
     * Override this if calling {@link #updateEnabledState()} on layer change events is not enough.
     * @return the {@link LayerChangeAdapter} that will be called on layer change events
     * @since 15404
     */
    protected LayerChangeAdapter buildLayerChangeAdapter() {
        return new LayerChangeAdapter();
    }

    /**
     * Override this if calling {@link #updateEnabledState()} on active layer change event is not enough.
     * @return the {@link LayerChangeAdapter} that will be called on active layer change event
     * @since 15404
     */
    protected ActiveLayerChangeAdapter buildActiveLayerChangeAdapter() {
        return new ActiveLayerChangeAdapter();
    }

    /**
     * Overwrite this if {@link #updateEnabledState()} should be called when the active / available layers change. Default is true.
     * @return <code>true</code> if a {@link LayerChangeListener} and a {@link ActiveLayerChangeListener} should be registered.
     * @since 10353
     */
    protected boolean listenToLayerChange() {
        return true;
    }

    /**
     * Overwrite this if {@link #updateEnabledState()} should be called when the selection changed. Default is true.
     * @return <code>true</code> if a {@link DataSelectionListener} should be registered.
     * @since 10353
     */
    protected boolean listenToSelectionChange() {
        return true;
    }

    @Override
    public void destroy() {
        if (sc != null && !sc.isAutomatic()) {
            MainApplication.unregisterActionShortcut(this);
        }
        if (layerChangeAdapter != null) {
            getLayerManager().removeLayerChangeListener(layerChangeAdapter);
            getLayerManager().removeActiveLayerChangeListener(activeLayerChangeAdapter);
        }
        if (selectionChangeAdapter != null) {
            SelectionEventManager.getInstance().removeSelectionListener(selectionChangeAdapter);
        }
        if (MainApplication.getToolbar() != null) {
            MainApplication.getToolbar().unregister(this);
        }
    }

    private void setHelpId() {
        String helpId = "Action/"+getClass().getName().substring(getClass().getName().lastIndexOf('.')+1);
        if (helpId.endsWith("Action")) {
            helpId = helpId.substring(0, helpId.length()-6);
        }
        setHelpId(helpId);
    }

    /**
     * Sets the help topic id.
     * @param helpId help topic id (result of {@link HelpUtil#ht})
     * @since 14397
     */
    protected void setHelpId(String helpId) {
        putValue("help", helpId);
    }

    /**
     * Sets the toolbar id.
     * @param toolbarId toolbar id
     * @since 16138
     */
    protected void setToolbarId(String toolbarId) {
        putValue("toolbar", toolbarId);
    }

    /**
     * Returns the shortcut for this action.
     * @return the shortcut for this action, or "No shortcut" if none is defined
     */
    public Shortcut getShortcut() {
        if (sc == null) {
            sc = Shortcut.registerShortcut("core:none", tr("No Shortcut"), KeyEvent.CHAR_UNDEFINED, Shortcut.NONE);
            // as this shortcut is shared by all action that don't want to have a shortcut,
            // we shouldn't allow the user to change it...
            // this is handled by special name "core:none"
        }
        return sc;
    }

    /**
     * Sets the tooltip text of this action.
     * @param tooltip The text to display in tooltip. Can be {@code null}
     */
    public final void setTooltip(String tooltip) {
        if (tooltip != null && sc != null) {
            sc.setTooltip(this, tooltip);
        } else if (tooltip != null) {
            putValue(SHORT_DESCRIPTION, tooltip);
        }
    }

    /**
     * Gets the layer manager used for this action. Defaults to the main layer manager but you can overwrite this.
     * <p>
     * The layer manager must be available when {@link #installAdapters()} is called and must not change.
     *
     * @return The layer manager.
     * @since 10353
     */
    public MainLayerManager getLayerManager() {
        return MainApplication.getLayerManager();
    }

    protected static void waitFuture(final Future<?> future, final PleaseWaitProgressMonitor monitor) {
        MainApplication.worker.submit(() -> {
                        try {
                            future.get();
                        } catch (InterruptedException | ExecutionException | CancellationException e) {
                            Logging.error(e);
                            return;
                        }
                        monitor.close();
                    });
    }

    /**
     * Override in subclasses to init the enabled state of an action when it is
     * created. Default behaviour is to call {@link #updateEnabledState()}
     *
     * @see #updateEnabledState()
     * @see #updateEnabledState(Collection)
     */
    protected void initEnabledState() {
        updateEnabledState();
    }

    /**
     * Override in subclasses to update the enabled state of the action when
     * something in the JOSM state changes, i.e. when a layer is removed or added.
     *
     * See {@link #updateEnabledState(Collection)} to respond to changes in the collection
     * of selected primitives.
     *
     * Default behavior is empty.
     *
     * @see #updateEnabledState(Collection)
     * @see #initEnabledState()
     * @see #listenToLayerChange()
     */
    protected void updateEnabledState() {
    }

    /**
     * Override in subclasses to update the enabled state of the action if the
     * collection of selected primitives changes. This method is called with the
     * new selection.
     *
     * @param selection the collection of selected primitives; may be empty, but not null
     *
     * @see #updateEnabledState()
     * @see #initEnabledState()
     * @see #listenToSelectionChange()
     */
    protected void updateEnabledState(Collection<? extends OsmPrimitive> selection) {
    }

    /**
     * Updates enabled state according to primitives currently selected in edit data set, if any.
     * Can be called in {@link #updateEnabledState()} implementations.
     * @see #updateEnabledStateOnCurrentSelection(boolean)
     * @since 10409
     */
    protected final void updateEnabledStateOnCurrentSelection() {
        updateEnabledStateOnCurrentSelection(false);
    }

    /**
     * Updates enabled state according to primitives currently selected in active data set, if any.
     * Can be called in {@link #updateEnabledState()} implementations.
     * @param allowReadOnly if {@code true}, read-only data sets are considered
     * @since 13434
     */
    protected final void updateEnabledStateOnCurrentSelection(boolean allowReadOnly) {
        DataSet ds = getLayerManager().getActiveDataSet();
        if (ds != null && (allowReadOnly || !ds.isLocked())) {
            updateEnabledState(ds.getSelected());
        } else {
            setEnabled(false);
        }
    }

    /**
     * Updates enabled state according to selected primitives, if any.
     * Enables action if the collection is not empty and references primitives in a modifiable data layer.
     * Can be called in {@link #updateEnabledState(Collection)} implementations.
     * @param selection the collection of selected primitives
     * @since 13434
     */
    protected final void updateEnabledStateOnModifiableSelection(Collection<? extends OsmPrimitive> selection) {
        setEnabled(OsmUtils.isOsmCollectionEditable(selection));
    }

    /**
     * Adapter for layer change events. Runs updateEnabledState() whenever the active layer changed.
     */
    protected class LayerChangeAdapter implements LayerChangeListener {
        @Override
        public void layerAdded(LayerAddEvent e) {
            updateEnabledState();
        }

        @Override
        public void layerRemoving(LayerRemoveEvent e) {
            updateEnabledState();
        }

        @Override
        public void layerOrderChanged(LayerOrderChangeEvent e) {
            updateEnabledState();
        }

        @Override
        public String toString() {
            return "LayerChangeAdapter [" + JosmAction.this + ']';
        }
    }

    /**
     * Adapter for layer change events. Runs updateEnabledState() whenever the active layer changed.
     */
    protected class ActiveLayerChangeAdapter implements ActiveLayerChangeListener {
        @Override
        public void activeOrEditLayerChanged(ActiveLayerChangeEvent e) {
            updateEnabledState();
        }

        @Override
        public String toString() {
            return "ActiveLayerChangeAdapter [" + JosmAction.this + ']';
        }
    }

    /**
     * Adapter for selection change events. Runs updateEnabledState() whenever the selection changed.
     */
    protected class SelectionChangeAdapter implements DataSelectionListener {
        @Override
        public void selectionChanged(SelectionChangeEvent event) {
            updateEnabledState(event.getSelection());
        }

        @Override
        public String toString() {
            return "SelectionChangeAdapter [" + JosmAction.this + ']';
        }
    }

    /**
     * Check whether user is about to operate on data outside of the download area.
     * Request confirmation if he is.
     * Also handles the case that there is no download area.
     *
     * @param operation the operation name which is used for setting some preferences
     * @param dialogTitle the title of the dialog being displayed
     * @param outsideDialogMessage the message text to be displayed when data is outside of the download area or no download area exists
     * @param incompleteDialogMessage the message text to be displayed when data is incomplete
     * @param primitives the primitives to operate on
     * @param ignore {@code null} or a primitive to be ignored
     * @return true, if operating on outlying primitives is OK; false, otherwise
     * @since 12749 (moved from Command)
     */
    public static boolean checkAndConfirmOutlyingOperation(String operation,
            String dialogTitle, String outsideDialogMessage, String incompleteDialogMessage,
            Collection<? extends OsmPrimitive> primitives,
            Collection<? extends OsmPrimitive> ignore) {
        int checkRes = Command.checkOutlyingOrIncompleteOperation(primitives, ignore);
        if ((checkRes & Command.IS_OUTSIDE) != 0) {
            boolean answer = showConfirmOutlyingOperationDialog(operation + "_outside_nodes", outsideDialogMessage, dialogTitle);
            if (!answer)
                return false;
        }
        if ((checkRes & Command.IS_INCOMPLETE) != 0) {
            boolean answer = showConfirmOutlyingOperationDialog(operation + "_incomplete", incompleteDialogMessage, dialogTitle);
            if (!answer)
                return false;
        }
        return true;
    }

    private static boolean showConfirmOutlyingOperationDialog(String preferenceKey, String dialogMessage, String dialogTitle) {
        JPanel msg = new JPanel(new GridBagLayout());
        msg.add(new JMultilineLabel("<html>" + dialogMessage + "</html>"));
        boolean answer = ConditionalOptionPaneUtil.showConfirmationDialog(
                preferenceKey,
                MainApplication.getMainFrame(),
                msg,
                dialogTitle,
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE,
                JOptionPane.YES_OPTION);
        if (!answer && JOptionPane.NO_OPTION == ConditionalOptionPaneUtil.getDialogReturnValue(preferenceKey)) {
            String message = tr("Operation was not performed, as per {0} preference", preferenceKey);
            new Notification(message).show();
            Logging.info(message);
        }
        return answer;
    }
}
