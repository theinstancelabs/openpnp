#!/usr/bin/env python3
"""Build the plan §10 config change set as a modified COPY of machine.xml (never edits the input).

    make_machine_patch.py --in ~/.openpnp2/machine.xml --out machine.xml.patched [--diff machine.xml.diff]
                          [--stock-compatible]

Items (plan docs/plan-2026-10-27-evidence-backed-roadmap.md §10):
  1  serial: port-name = /dev/serial/by-id/... (stable path) + usb-serial-number / usb-description
     (feat/serial-autodetect: match by USB serial, then description, then the port name)
  2  VAC1 / VAC2: disabled-actuation="ActuateOff"
  3  DISABLE_COMMAND: M410, M107 P3, M107 P2, M107 P1, M107 P0, M18 B   (§4.4)
  4  HOME_COMMAND: existing lines + G92 A0 B0 after G28                  (§4.3)
  6  N2: no compatible nozzle tips and no loaded tip (checked; already true in the live file)
  7  CONNECT_COMMAND: + M906 B540 (auger motor current, §4.2 / §10 item 7)
  8  VAC2 valve line: M106 P3 S180 -> M106 P3 S255                       (§4.2, approved)

Edits are textual and anchored: every anchor must match exactly once, otherwise the tool stops
without writing anything. The output keeps the input's formatting so the diff shows only the
changes. Python 3 standard library only.
"""
import argparse
import difflib
import re
import sys
import xml.etree.ElementTree as ET

BY_ID = "/dev/serial/by-id/usb-STMicroelectronics_MARLIN_OPULO_LUMEN_REV5_CDC_in_FS_Mode_387932683335-if00"
USB_SERIAL = "387932683335"
USB_DESCRIPTION = "MARLIN_OPULO_LUMEN_REV5"
DISABLE_LINES = ["M410", "M107 P3", "M107 P2", "M107 P1", "M107 P0", "M18 B"]
HOME_EXTRA = "G92 A0 B0 ; OpenPnP assumes A=B=0 after homing; Marlin boots at A=B=720 (plan 4.3)"
CONNECT_EXTRA = "M906 B540 ; auger motor (J/B) current, mA (plan 10 item 7)"


class PatchError(Exception):
    pass


def sub_once(text, pattern, repl, what, flags=0):
    n = len(re.findall(pattern, text, flags))
    if n != 1:
        raise PatchError("%s: expected exactly 1 match, found %d (pattern %r)" % (what, n, pattern))
    return re.sub(pattern, repl, text, count=1, flags=flags)


def patch(src, stock_compatible=False):
    out = src
    notes = []

    # 1. serial port: stable by-id path + USB identity for auto-detect. Stock OpenPnP (2.6 in
    #    /opt/openpnp, 2.7 without feat/serial-autodetect) refuses unknown attributes, so the
    #    stock-compatible variant keeps only the by-id path.
    def serial_repl(m):
        el = m.group(0)
        el = re.sub(r'port-name="[^"]*"', 'port-name="%s"' % BY_ID, el, count=1)
        if stock_compatible:
            return re.sub(r' usb-(serial-number|description)="[^"]*"', "", el)
        for attr, val in (("usb-serial-number", USB_SERIAL), ("usb-description", USB_DESCRIPTION)):
            if re.search(r'\b%s="' % attr, el):
                el = re.sub(r'\b%s="[^"]*"' % attr, '%s="%s"' % (attr, val), el, count=1)
            else:
                el = el.replace(' name="SerialPortCommunications"',
                                ' %s="%s" name="SerialPortCommunications"' % (attr, val), 1)
        return el
    out = sub_once(out, r'<serial [^>]*/>', serial_repl, "1 serial element")
    if stock_compatible:
        notes.append("1 serial: port-name=%s (stock-compatible: no usb-* auto-detect attributes)" % BY_ID)
    else:
        notes.append("1 serial: port-name=%s usb-serial-number=%s usb-description=%s" % (BY_ID, USB_SERIAL, USB_DESCRIPTION))

    # 2. VAC1 / VAC2 disabled-actuation.
    for name in ("VAC1", "VAC2"):
        pat = r'(<actuator [^>]*\bname="%s"[^>]*\bdisabled-actuation=")[A-Za-z]+(")' % name
        out = sub_once(out, pat, r"\g<1>ActuateOff\g<2>", "2 %s disabled-actuation" % name)
    notes.append("2 VAC1, VAC2: disabled-actuation=ActuateOff")

    # 3. DISABLE_COMMAND (inserted after CONNECT_COMMAND; replaced if one exists).
    texts = "".join("               <text><![CDATA[%s]]></text>\n" % l for l in DISABLE_LINES)
    block = '            <command type="DISABLE_COMMAND">\n%s            </command>\n' % texts
    if re.search(r'<command type="DISABLE_COMMAND">', out):
        out = sub_once(out, r'            <command type="DISABLE_COMMAND">\n.*?            </command>\n',
                       lambda m: block, "3 DISABLE_COMMAND", re.S)
    else:
        out = sub_once(out, r'(            <command type="CONNECT_COMMAND">\n.*?            </command>\n)',
                       lambda m: m.group(1) + block, "3 CONNECT_COMMAND anchor", re.S)
    notes.append("3 DISABLE_COMMAND: " + ", ".join(DISABLE_LINES))

    # 4. HOME_COMMAND: G92 A0 B0 right after the G28 line.
    def home_repl(m):
        body = m.group(0)
        if "G92 A0 B0" in body:
            return body
        g28 = re.search(r'( *)<text><!\[CDATA\[G28[^\]]*\]\]></text>\n', body)
        if not g28:
            raise PatchError("4 HOME_COMMAND has no G28 line")
        ins = '%s<text><![CDATA[%s]]></text>\n' % (g28.group(1), HOME_EXTRA)
        return body[:g28.end()] + ins + body[g28.end():]
    out = sub_once(out, r'<command type="HOME_COMMAND">\n.*?</command>\n', home_repl, "4 HOME_COMMAND", re.S)
    notes.append("4 HOME_COMMAND: + " + HOME_EXTRA)

    # 6. N2: no compatible nozzle tips, no loaded tip (check; fix if needed).
    m = re.search(r'(<nozzle [^>]*\bname="N2"[^>]*>)\n(\s*)<compatible-nozzle-tip-ids class="java.util.ArrayList"(/>|>.*?</compatible-nozzle-tip-ids>)',
                  out, re.S)
    if not m:
        raise PatchError("6 N2 nozzle / compatible-nozzle-tip-ids not found")
    n2_open = m.group(1)
    if m.group(3) != "/>" or "current-nozzle-tip-id=" in n2_open:
        new_open = re.sub(r' current-nozzle-tip-id="[^"]*"', "", n2_open)
        out = out.replace(m.group(0), '%s\n%s<compatible-nozzle-tip-ids class="java.util.ArrayList"/>'
                          % (new_open, m.group(2)), 1)
        notes.append("6 N2: compatible nozzle tips and loaded tip removed")
    else:
        notes.append("6 N2: already has no compatible nozzle tips and no loaded tip (no change)")

    # 7. CONNECT_COMMAND: M906 B540 as the last line.
    def connect_repl(m):
        body = m.group(0)
        if "M906 B" in body:
            raise PatchError("7 CONNECT_COMMAND already sets M906 B; check by hand")
        last = body.rfind("            </command>")
        indent = re.findall(r'\n( *)<text>', body)[-1]
        return body[:last] + '%s<text><![CDATA[%s]]></text>\n' % (indent, CONNECT_EXTRA) + body[last:]
    out = sub_once(out, r'<command type="CONNECT_COMMAND">\n.*?            </command>', connect_repl,
                   "7 CONNECT_COMMAND", re.S)
    notes.append("7 CONNECT_COMMAND: + " + CONNECT_EXTRA)

    # 8. VAC2 right valve S180 -> S255 (only the P3 line of the VAC2 boolean command).
    vac2 = re.search(r'<actuator [^>]*\bid="([^"]+)"[^>]*\bname="VAC2"', out)
    if not vac2:
        raise PatchError("8 VAC2 actuator id not found")
    pat = (r'(<command head-mountable-id="%s" type="ACTUATE_BOOLEAN_COMMAND">\n(?:.*?\n)*?.*?)'
           r'\{True:M106 P3 S180\}' % re.escape(vac2.group(1)))
    out = sub_once(out, pat, r"\g<1>{True:M106 P3 S255}", "8 VAC2 valve S180")
    notes.append("8 VAC2 (%s): valve line M106 P3 S180 -> M106 P3 S255" % vac2.group(1))

    ET.fromstring(out.encode("utf-8"))  # still well-formed XML
    return out, notes


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--in", dest="src", required=True, help="machine.xml to read (never written)")
    ap.add_argument("--out", required=True, help="patched copy to write")
    ap.add_argument("--diff", help="write a unified diff here")
    ap.add_argument("--stock-compatible", action="store_true",
                    help="omit the usb-serial-number / usb-description attributes (only the fork reads them)")
    a = ap.parse_args(argv)
    if a.out == a.src:
        raise SystemExit("refusing to overwrite the input")
    with open(a.src, encoding="utf-8") as f:
        src = f.read()
    try:
        out, notes = patch(src, a.stock_compatible)
    except PatchError as e:
        raise SystemExit("not patched: %s" % e)
    with open(a.out, "w", encoding="utf-8") as f:
        f.write(out)
    if a.diff:
        d = difflib.unified_diff(src.splitlines(True), out.splitlines(True), fromfile="a/machine.xml",
                                 tofile="b/machine.xml")
        with open(a.diff, "w", encoding="utf-8") as f:
            f.writelines(d)
    for n in notes:
        print(n)


if __name__ == "__main__":
    sys.exit(main())
