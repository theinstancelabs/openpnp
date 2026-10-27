package org.openpnp.instancelabs.serial.gui;

import java.awt.Color;
import java.awt.event.HierarchyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.UIManager;
import javax.swing.border.TitledBorder;

import org.openpnp.instancelabs.serial.SerialPortIdentity;
import org.openpnp.instancelabs.serial.SerialPortMatch;
import org.openpnp.instancelabs.serial.SerialPortMatchException;
import org.openpnp.instancelabs.serial.SerialPortMatcher;
import org.openpnp.machine.reference.driver.SerialPortCommunications;
import org.pmw.tinylog.Logger;

import com.jgoodies.forms.layout.ColumnSpec;
import com.jgoodies.forms.layout.FormLayout;
import com.jgoodies.forms.layout.FormSpecs;
import com.jgoodies.forms.layout.RowSpec;

/**
 * Driver settings panel for serial auto-detect: the optional USB serial number / description,
 * a "Detect..." button that fills them from the enumerated ports, a "Check" button that shows
 * which port would be opened, and the identity actually used by the last connect.
 *
 * The two text fields are bound by the owning wizard (so Apply/Reset work as for every other
 * field); this panel only reads them.
 */
public class SerialAutoDetectPanel extends JPanel {
    private static final long serialVersionUID = 1L;

    /** Pre-selected in the Detect dialog when present. */
    public static final String LUMEN_DESCRIPTION = "MARLIN_OPULO_LUMEN";

    private final SerialPortCommunications serial;
    private final Supplier<String> portNameSupplier;

    private final JTextField usbSerialNumber;
    private final JTextField usbDescription;
    private final JTextArea matchInfo;

    /**
     * @param serial the communications whose last match is shown and whose matcher is used;
     *        may be null (then the default matcher is used and no last match is shown).
     * @param portNameSupplier the port name currently shown in the wizard (may be unapplied).
     */
    public SerialAutoDetectPanel(SerialPortCommunications serial,
            Supplier<String> portNameSupplier) {
        this.serial = serial;
        this.portNameSupplier = portNameSupplier;

        setBorder(new TitledBorder(null, "Serial Auto-Detect (USB identity)",
                TitledBorder.LEADING, TitledBorder.TOP, null, null));
        setLayout(new FormLayout(new ColumnSpec[] {
                FormSpecs.RELATED_GAP_COLSPEC,
                ColumnSpec.decode("right:max(70dlu;default)"),
                FormSpecs.RELATED_GAP_COLSPEC,
                ColumnSpec.decode("max(160dlu;default):grow"),
                FormSpecs.RELATED_GAP_COLSPEC,
                FormSpecs.DEFAULT_COLSPEC,},
            new RowSpec[] {
                FormSpecs.RELATED_GAP_ROWSPEC,
                FormSpecs.DEFAULT_ROWSPEC,
                FormSpecs.RELATED_GAP_ROWSPEC,
                FormSpecs.DEFAULT_ROWSPEC,
                FormSpecs.RELATED_GAP_ROWSPEC,
                FormSpecs.DEFAULT_ROWSPEC,
                FormSpecs.RELATED_GAP_ROWSPEC,
                FormSpecs.DEFAULT_ROWSPEC,}));

        JLabel lblSerial = new JLabel("USB serial number");
        lblSerial.setToolTipText("<html>Tried first. Exact match on the USB serial number "
                + "(unique per board).<br/>Leave blank to skip.</html>");
        add(lblSerial, "2, 2, right, default");
        usbSerialNumber = new JTextField();
        usbSerialNumber.setColumns(20);
        add(usbSerialNumber, "4, 2, fill, default");

        JButton detect = new JButton("Detect...");
        detect.setToolTipText("List the serial ports with their USB identity and fill in the "
                + "fields from the one you choose (ports are listed, not opened).");
        detect.addActionListener(e -> detect());
        add(detect, "6, 2");

        JLabel lblDescription = new JLabel("USB description contains");
        lblDescription.setToolTipText("<html>Tried second. Case-insensitive substring of the USB "
                + "description, e.g. MARLIN_OPULO_LUMEN_REV5.<br/>Shared by all boards of a "
                + "model, so ambiguous if two are plugged in.<br/>If neither field matches, "
                + "the Port above (name or /dev/serial/by-id/... path) is used.</html>");
        add(lblDescription, "2, 4, right, default");
        usbDescription = new JTextField();
        usbDescription.setColumns(20);
        add(usbDescription, "4, 4, fill, default");

        JButton check = new JButton("Check");
        check.setToolTipText("Show which port would be opened with the values shown "
                + "(including unapplied edits). Does not open the port.");
        check.addActionListener(e -> check());
        add(check, "6, 4");

        JLabel lblMatched = new JLabel("Matched device");
        add(lblMatched, "2, 6, right, top");
        matchInfo = new JTextArea(4, 40);
        matchInfo.setEditable(false);
        matchInfo.setLineWrap(true);
        matchInfo.setWrapStyleWord(true);
        Color bg = UIManager.getColor("Panel.background");
        if (bg != null) {
            matchInfo.setBackground(bg);
        }
        // In a scroll pane so the wrapped text's preferred size does not depend on the
        // not-yet-known width (otherwise FormLayout's first pass makes the row very tall).
        JScrollPane matchScroll = new JScrollPane(matchInfo,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        matchScroll.setBorder(null);
        add(matchScroll, "4, 6, 3, 1, fill, fill");

        // Refresh the "last connect" text whenever the panel is shown.
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) {
                showLastMatch();
            }
        });
        showLastMatch();
    }

    public JTextField getUsbSerialNumberField() {
        return usbSerialNumber;
    }

    public JTextField getUsbDescriptionField() {
        return usbDescription;
    }

    private SerialPortMatcher matcher() {
        return serial != null ? serial.getPortMatcher() : SerialPortMatcher.createDefault();
    }

    /**
     * Text describing the last connect's match.
     */
    public static String describeLastMatch(SerialPortMatch match) {
        if (match == null) {
            return "Not connected since start-up. Use Check to preview.";
        }
        StringBuilder sb = new StringBuilder("Last connect: ").append(match.getSummary());
        for (String w : match.getWarnings()) {
            sb.append("\nWarning: ").append(w);
        }
        return sb.toString();
    }

    private void showLastMatch() {
        matchInfo.setText(describeLastMatch(serial == null ? null : serial.getLastMatch()));
        matchInfo.setCaretPosition(0);
    }

    private void check() {
        String text;
        try {
            SerialPortMatch match = matcher().resolve(usbSerialNumber.getText(),
                    usbDescription.getText(), portNameSupplier.get());
            StringBuilder sb = new StringBuilder("Would open: ").append(match.getSummary());
            for (String w : match.getWarnings()) {
                sb.append("\nWarning: ").append(w);
            }
            text = sb.toString();
        }
        catch (SerialPortMatchException ex) {
            text = "No match: " + ex.getMessage();
        }
        catch (RuntimeException ex) {
            Logger.warn(ex, "Serial auto-detect check failed");
            text = "Check failed: " + ex;
        }
        matchInfo.setText(text);
        matchInfo.setCaretPosition(0);
    }

    private void detect() {
        List<SerialPortIdentity> ports;
        try {
            ports = new ArrayList<>(matcher().enumerate());
        }
        catch (RuntimeException | LinkageError ex) {
            JOptionPane.showMessageDialog(this, "Serial port enumeration failed: " + ex,
                    "Detect", JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (ports.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No serial ports found.", "Detect",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        JComboBox<SerialPortIdentity> choice =
                new JComboBox<>(ports.toArray(new SerialPortIdentity[0]));
        SerialPortIdentity preferred = preferred(ports);
        if (preferred != null) {
            choice.setSelectedItem(preferred);
        }
        int result = JOptionPane.showConfirmDialog(this,
                new Object[] {"Choose the controller's serial port:", choice},
                "Detect serial port", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (result != JOptionPane.OK_OPTION || choice.getSelectedItem() == null) {
            return;
        }
        SerialPortIdentity chosen = (SerialPortIdentity) choice.getSelectedItem();
        usbSerialNumber.setText(chosen.hasSerialNumber() ? chosen.getSerialNumber() : "");
        usbDescription.setText(chosen.getBestDescription());
        matchInfo.setText("Selected " + chosen.toDisplayString()
                + "\nPress Apply to save. The next connect will use it.");
        matchInfo.setCaretPosition(0);
    }

    /**
     * The first LumenPnP board if any, else the first port with a USB serial number, else null.
     */
    public static SerialPortIdentity preferred(List<SerialPortIdentity> ports) {
        for (SerialPortIdentity p : ports) {
            if (p.descriptionContains(LUMEN_DESCRIPTION)) {
                return p;
            }
        }
        for (SerialPortIdentity p : ports) {
            if (p.hasSerialNumber() && !p.getBestDescription().isEmpty()) {
                return p;
            }
        }
        return null;
    }
}
