# Copyright 2026 the original author or authors.
# SPDX-License-Identifier: Apache-2.0
"""
Asterion Solver for Siril: blind plate solving in a fraction of a second.

A wrapper around the ``asterion`` command line tool of Asterion Solver
(https://github.com/asterion-solver/asterion). It solves the image loaded in
Siril blindly, without needing its position or its scale, and writes the
solution in the image: a TAN projection with SIP distortion polynomials, which
Siril's own tools then use (annotations, photometry, mosaics...). It also lists
the star catalogs of Asterion, and downloads them.

The script offers a PyQt6 graphical interface when launched from the Scripts
menu, and a command line interface for use with the ``pyscript`` command.

Asterion Solver is a separate program, which must be installed with a catalog
(``asterion --download-catalog tycho2``, or from this script). Its location is
configured inside this script, and remembered.

Usage (GUI):
    Launch from the Siril Scripts menu.

Usage (CLI, via the pyscript command):
    pyscript AsterionSolver.py --solve
    pyscript AsterionSolver.py --solve --no-hints
    pyscript AsterionSolver.py --list-catalogs
    pyscript AsterionSolver.py --download-catalog tycho2
    pyscript AsterionSolver.py --executable /path/to/asterion --solve

Report bugs with this script to https://github.com/asterion-solver/asterion/issues

Author: Cedric Champeau
"""

# Version history
# 1.0.0 - Initial release: blind plate solving of the loaded image, catalog
#         listing and download, PyQt6 GUI and command line.

import sirilpy as s

import os
import re
import sys
import json
import shutil
import tempfile
import argparse
import subprocess
from pathlib import Path

from sirilpy import LogColor

VERSION = "1.0.0"
CONFIG_SUBDIR = "AsterionSolver"
CONFIG_FILENAME = "settings.json"
WEBSITE = "https://github.com/asterion-solver/asterion"
EXE_NAMES = ("asterion", "asterion.exe")

# The keywords of a previous solution, which the new one replaces
SOLUTION_KEYWORDS = re.compile(
    r"^(WCSAXES|CTYPE\d|CUNIT\d|CRPIX\d|CRVAL\d|CDELT\d|CROTA\d|CD\d_\d|PC\d_\d|PV\d_\d+|"
    r"LONPOLE|LATPOLE|EQUINOX|RADESYS|RADECSYS|[AB]P?_ORDER|[AB]P?_\d+_\d+|[AB]_DMAX|PLTSOLVD)$")


class AsterionError(Exception):
    pass


# ---------------------------------------------------------------------------
# Settings persistence
# ---------------------------------------------------------------------------

class Options:
    def __init__(self, executable="", use_hints=True, list_objects=True):
        self.executable = executable
        self.use_hints = use_hints
        self.list_objects = list_objects

    @staticmethod
    def from_dict(data):
        return Options(str(data.get("executable", "")), bool(data.get("use_hints", True)),
                       bool(data.get("list_objects", True)))

    def to_dict(self):
        return {"executable": self.executable, "use_hints": self.use_hints, "list_objects": self.list_objects}


def settings_path(siril):
    cfg_dir = Path(siril.get_siril_configdir()) / CONFIG_SUBDIR
    cfg_dir.mkdir(parents=True, exist_ok=True)
    return cfg_dir / CONFIG_FILENAME


def load_options(siril):
    try:
        return Options.from_dict(json.loads(settings_path(siril).read_text(encoding="utf-8")))
    except Exception:
        return Options()


def save_options(siril, opts):
    try:
        settings_path(siril).write_text(json.dumps(opts.to_dict(), indent=2), encoding="utf-8")
    except Exception as exc:
        siril.log(f"Asterion: could not save settings: {exc}", LogColor.SALMON)


# ---------------------------------------------------------------------------
# The asterion executable. Its path may be wrong: nothing here throws for that,
# every call tells why it could not run.
# ---------------------------------------------------------------------------

def _default_install_dirs():
    home = Path.home()
    if sys.platform == "win32":
        local = os.environ.get("LOCALAPPDATA")
        return [str(Path(local) / "Programs" / "asterion")] if local else []
    return [str(home / ".local" / "bin"), str(home / "bin"), "/usr/local/bin", "/opt/homebrew/bin"]


def find_on_path():
    for name in EXE_NAMES:
        found = shutil.which(name)
        if found:
            return found
    for d in _default_install_dirs():
        for name in EXE_NAMES:
            candidate = os.path.join(d, name)
            if os.path.isfile(candidate) and os.access(candidate, os.X_OK):
                return candidate
    return None


def resolve_exe(configured):
    """The executable to run: the configured one when it exists, otherwise the
    one found on the PATH or in the usual install directories, or None."""
    if configured and configured.strip():
        candidate = os.path.expanduser(configured.strip())
        if os.path.isfile(candidate) and os.access(candidate, os.X_OK):
            return candidate
        on_path = shutil.which(candidate)
        if on_path:
            return on_path
        return None
    return find_on_path()


def _run(exe, args, timeout):
    """Runs asterion, and returns (exit code, stdout, stderr)."""
    proc = subprocess.run([exe, *args], stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout,
                          text=True, encoding="utf-8", errors="replace")
    return proc.returncode, proc.stdout, proc.stderr


def probe(configured):
    """Checks the executable: (path, version) when it runs, or raises
    AsterionError telling why it doesn't."""
    exe = resolve_exe(configured)
    if exe is None:
        if configured and configured.strip():
            raise AsterionError(f"{configured} doesn't exist or isn't executable.")
        raise AsterionError("Asterion Solver wasn't found: install it, or set the path of the asterion executable.")
    try:
        code, out, err = _run(exe, ["--version"], 10)
    except (OSError, subprocess.SubprocessError) as exc:
        raise AsterionError(f"Could not run {exe}: {exc}")
    version = out.strip()
    if code != 0 or not version.startswith("Asterion Solver"):
        raise AsterionError(f"{exe} is not Asterion Solver: it printed \"{(version or err.strip())[:60]}\"")
    return exe, version


def list_catalogs(exe, version):
    """The catalogs, as asterion describes them: {directory, installed, available}."""
    try:
        code, out, err = _run(exe, ["--list-catalogs", "--json"], 60)
    except (OSError, subprocess.SubprocessError) as exc:
        raise AsterionError(f"Could not run {exe}: {exc}")
    if code != 0:
        raise AsterionError(f"{exe} --list-catalogs failed: {err.strip() or code}")
    try:
        return json.loads(out)
    except ValueError:
        # versions before 0.1.8 list their catalogs as text only
        raise AsterionError(f"{version} can't list its catalogs for this script: update Asterion Solver "
                            "to 0.1.8 or later to manage them from here. Solving works.")


def download_command(exe, name):
    return [exe, "--download-catalog", name, "--plain"]


# ---------------------------------------------------------------------------
# FITS header of the solution
# ---------------------------------------------------------------------------

def fits_card(key, value, comment=""):
    """A FITS header card of 80 characters."""
    if isinstance(value, bool):
        text = ("T" if value else "F").rjust(20)
    elif isinstance(value, int):
        text = str(value).rjust(20)
    elif isinstance(value, float):
        text = f"{value:.15G}"
        if "." not in text and "E" not in text:
            text += ".0"
        text = text.rjust(20)
    else:
        text = "'" + str(value).replace("'", "''").ljust(8) + "'"
        text = text.ljust(20)
    card = f"{key[:8]:<8}= {text}"
    if comment:
        card += " / " + comment
    return card[:80].ljust(80)


def header_cards(header):
    """The cards of a header string: separated by new lines, or 80 characters each. Siril
    ends the string with a NUL character, after the END card."""
    if not header:
        return []
    header = header.replace("\x00", "")
    if "\n" in header:
        return [line.rstrip("\r") for line in header.split("\n") if line.strip()]
    return [header[i:i + 80] for i in range(0, len(header), 80) if header[i:i + 80].strip()]


def header_value(header, key):
    """The value of a keyword of a header string, without its quotes, or None."""
    for card in header_cards(header):
        if card[:8].strip() == key and card[8:10] == "= ":
            return card[10:].split("/")[0].strip().strip("'").strip()
    return None


SIP_COEFFICIENT = re.compile(r"^(A|B|AP|BP)_(\d+)_(\d+)$")


def flip_rows(wcs, height):
    """The solution for the image with its rows in the other order: the row y of one is the
    row height + 1 - y of the other, so the ordinates relative to the reference pixel change
    sign, the CD matrix changes the sign of its second column, and a SIP coefficient of v^q
    changes sign when q is odd, those which correct ordinates changing sign once more."""
    flipped = {}
    for key, value in wcs.items():
        m = SIP_COEFFICIENT.match(key)
        if key == "CRPIX2":
            value = height + 1 - value
        elif key in ("CD1_2", "CD2_2", "PC1_2", "PC2_2"):
            value = -value
        elif m:
            sign = -1 if int(m.group(3)) % 2 else 1
            if m.group(1) in ("B", "BP"):
                sign = -sign
            value = sign * value
        flipped[key] = value
    return flipped


def merge_solution(header, wcs, comment):
    """The header of the image, with the keywords of the solution instead of the ones of
    a previous solution."""
    kept = []
    for card in header_cards(header):
        key = card[:8].strip()
        if key == "END" or SOLUTION_KEYWORDS.match(key):
            continue
        kept.append(card.ljust(80)[:80] if len(card) <= 80 else card)
    solution = [fits_card(key, value, comment) for key, value in wcs.items()]
    solution.append(fits_card("PLTSOLVD", True, comment))
    return "\n".join(kept + solution + ["END".ljust(80)])


# ---------------------------------------------------------------------------
# Solving
# ---------------------------------------------------------------------------

def mono16(data):
    """The image as a single channel of 16-bit integers: asterion solves color images from
    the sum of their channels anyway, and a large color image in 32-bit floats would be
    6 times as long to write, and to read. The pixel grid is the same. The image can have
    a hundred million pixels: the sum is computed in place, in few passes."""
    import numpy as np
    if data.ndim == 3:
        # (channels, height, width)
        channels = data.shape[0]
        mono = np.add(data[0], data[1], dtype=np.float32) if channels > 1 else data[0].astype(np.float32)
        for c in range(2, channels):
            np.add(mono, data[c], out=mono)
    else:
        channels = 1
        mono = data.astype(np.float32)
    if np.issubdtype(data.dtype, np.floating):
        # Siril's floating point images are within [0, 1], unless processing made them brighter
        peak = float(mono.max()) if mono.size else 0.0
        if not np.isfinite(peak):
            np.nan_to_num(mono, copy=False, nan=0.0, posinf=0.0, neginf=0.0)
            peak = float(mono.max())
        scale = 65535.0 / max(float(channels), peak)
    else:
        scale = 1.0 / channels
    np.multiply(mono, scale, out=mono)
    np.clip(mono, 0, 65535, out=mono)
    return mono.astype(np.uint16)


def solve_loaded_image(siril, opts, log=None):
    """Solves the image loaded in Siril, and writes the solution in it.

    Returns the result of asterion, as a dict, or None when the image isn't solved."""
    log = log or (lambda text, color=LogColor.DEFAULT: siril.log(text, color))
    exe, version = probe(opts.executable)
    if not siril.is_image_loaded():
        raise AsterionError("No image is loaded: open one first.")
    log(f"Asterion: {version}")
    work = tempfile.mkdtemp(prefix="asterion-")
    file = os.path.join(work, "image.fit")
    try:
        # the image as it is now, which may have been processed since it was loaded
        image = siril.get_image(with_pixels=True)
        if image is None:
            raise AsterionError("Could not read the loaded image.")
        if not siril.save_image_file(mono16(image.data), header=image.header, filename=file):
            raise AsterionError("Could not write the image to a temporary file.")
        args = ["--json", "--plain"]
        if not opts.use_hints:
            args.append("--no-hints")
        args.append(file)
        try:
            code, out, err = _run(exe, args, 300)
        except subprocess.TimeoutExpired:
            raise AsterionError("asterion didn't answer within 5 minutes.")
        except (OSError, subprocess.SubprocessError) as exc:
            raise AsterionError(f"Could not run {exe}: {exc}")
        if code == 2 or not out.strip():
            raise AsterionError(f"asterion failed: {err.strip() or 'exit code ' + str(code)}")
        result = json.loads(out)
        if isinstance(result, list):
            result = result[0]
    finally:
        shutil.rmtree(work, ignore_errors=True)

    timing = result.get("timing", {})
    if not result.get("solved"):
        log(f"Asterion: not solved, {result.get('stars', 0)} stars detected, "
            f"{timing.get('totalMillis', 0):.0f} ms", LogColor.SALMON)
        return None

    comment = f"Asterion Solver, {result.get('index', '')}".strip(", ")
    current = siril.get_image_fits_header()
    wcs = result["wcs"]
    # Siril keeps its solutions in bottom-up pixel coordinates, also for the images whose
    # rows are top-down, which asterion solves as they are stored
    if (header_value(current, "ROWORDER") or "").upper() == "TOP-DOWN":
        height = int(header_value(current, "NAXIS2"))
        wcs = flip_rows(wcs, height)
    header = merge_solution(current, wcs, comment)
    siril.undo_save_state("Asterion plate solving")
    with siril.image_lock():
        siril.set_image_metadata_from_header_string(header)

    log(f"Asterion: solved in {timing.get('totalMillis', 0):.0f} ms "
        f"(read {timing.get('readMillis', 0):.0f}, detect {timing.get('detectMillis', 0):.0f}, "
        f"solve {timing.get('solveMillis', 0):.0f})", LogColor.GREEN)
    log(f"  Center:    {result['ra']:.5f}°, {result['dec']:+.5f}°")
    log(f"  Scale:     {result['scale']:.4f}\"/px")
    log(f"  Field:     {result['fieldWidth']:.3f}° x {result['fieldHeight']:.3f}°")
    if result.get("focalLength"):
        log(f"  Focal:     {result['focalLength']:.0f} mm ({result['pixelSize']:.2f} µm pixels)")
    log(f"  Rotation:  {result['rotation']:.3f}° east of north" + (", flipped" if result.get("flipped") else ""))
    log(f"  Stars:     {result['matchedStars']} matched out of {result['stars']}, "
        f"RMS {result['rmsArcsec']:.2f}\" ({result['rmsArcsec'] / result['scale']:.2f} px)")
    objects = result.get("objects") or []
    if opts.list_objects and objects:
        names = [o["name"] + (f" ({o['commonName']})" if o.get("commonName") else "") for o in objects]
        more = f", and {len(names) - 12} more" if len(names) > 12 else ""
        log(f"  Objects:   {', '.join(names[:12])}{more}")
    return result


# ---------------------------------------------------------------------------
# Command line
# ---------------------------------------------------------------------------

def run_cli(siril, argv):
    parser = argparse.ArgumentParser(prog="AsterionSolver.py", description="Blind plate solving with Asterion Solver")
    parser.add_argument("--executable", help="the path of the asterion executable, remembered for the next runs")
    parser.add_argument("--solve", action="store_true", help="solve the loaded image (the default)")
    parser.add_argument("--no-hints", action="store_true", help="ignore the position and the scale of the header")
    parser.add_argument("--list-catalogs", action="store_true", help="list the installed and available catalogs")
    parser.add_argument("--download-catalog", metavar="NAME", help="download and install a catalog")
    args = parser.parse_args(argv)

    opts = load_options(siril)
    if args.executable:
        opts.executable = args.executable
        save_options(siril, opts)
    if args.no_hints:
        opts.use_hints = False
    try:
        exe, version = probe(opts.executable)
        if args.list_catalogs:
            catalogs = list_catalogs(exe, version)
            siril.log(f"Asterion: catalogs in {catalogs['directory']}")
            for c in catalogs["installed"]:
                siril.log(f"  {c['name']}: installed, {c['size'] / 1048576:.0f} MB")
            for c in catalogs["available"]:
                if not c["installed"]:
                    siril.log(f"  {c['name']}: {c['description']}")
        if args.download_catalog:
            siril.log(f"Asterion: downloading {args.download_catalog}")
            proc = subprocess.Popen(download_command(exe, args.download_catalog), stdout=subprocess.PIPE,
                                    stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace")
            for line in proc.stdout:
                if line.strip():
                    siril.log("  " + line.rstrip())
            if proc.wait() != 0:
                raise AsterionError(f"Downloading {args.download_catalog} failed")
            siril.log(f"Asterion: {args.download_catalog} is installed", LogColor.GREEN)
        if args.solve or not (args.list_catalogs or args.download_catalog):
            return 0 if solve_loaded_image(siril, opts) else 1
        return 0
    except AsterionError as exc:
        siril.log(f"Asterion: {exc}", LogColor.RED)
        return 1


# ---------------------------------------------------------------------------
# Graphical interface
# ---------------------------------------------------------------------------

def run_gui(siril):
    s.ensure_installed("PyQt6")
    from PyQt6.QtCore import Qt, QProcess
    from PyQt6.QtWidgets import (
        QApplication, QDialog, QVBoxLayout, QHBoxLayout, QGroupBox, QLabel, QLineEdit, QPushButton,
        QCheckBox, QTableWidget, QTableWidgetItem, QHeaderView, QFileDialog, QAbstractItemView, QMessageBox)

    class AsterionDialog(QDialog):
        def __init__(self):
            super().__init__()
            self.opts = load_options(siril)
            self.exe = None
            self.version = None
            self.catalogs = None
            self.process = None
            self.setWindowTitle(f"Asterion Solver {VERSION}")
            self.setMinimumWidth(640)
            layout = QVBoxLayout(self)

            intro = QLabel(f"Solves the loaded image blindly with <a href=\"{WEBSITE}\">Asterion Solver</a>, "
                           "and writes the solution in the image.")
            intro.setOpenExternalLinks(True)
            intro.setWordWrap(True)
            layout.addWidget(intro)

            row = QHBoxLayout()
            row.addWidget(QLabel("Executable:"))
            self.exe_edit = QLineEdit(self.opts.executable)
            self.exe_edit.setPlaceholderText("asterion, found on the PATH")
            self.exe_edit.setToolTip("The asterion executable: its path, or empty to find it on the PATH.")
            self.exe_edit.editingFinished.connect(self.executable_changed)
            row.addWidget(self.exe_edit, 1)
            browse = QPushButton("Browse…")
            browse.clicked.connect(self.browse)
            row.addWidget(browse)
            layout.addLayout(row)

            self.status = QLabel()
            self.status.setWordWrap(True)
            self.status.setTextInteractionFlags(Qt.TextInteractionFlag.TextSelectableByMouse)
            layout.addWidget(self.status)

            group = QGroupBox("Catalogs")
            group_layout = QVBoxLayout(group)
            self.table = QTableWidget(0, 3)
            self.table.setHorizontalHeaderLabels(["Catalog", "Status", "Content"])
            self.table.verticalHeader().setVisible(False)
            self.table.setSelectionBehavior(QAbstractItemView.SelectionBehavior.SelectRows)
            self.table.setSelectionMode(QAbstractItemView.SelectionMode.SingleSelection)
            self.table.setEditTriggers(QAbstractItemView.EditTrigger.NoEditTriggers)
            header = self.table.horizontalHeader()
            header.setSectionResizeMode(0, QHeaderView.ResizeMode.ResizeToContents)
            header.setSectionResizeMode(1, QHeaderView.ResizeMode.ResizeToContents)
            header.setSectionResizeMode(2, QHeaderView.ResizeMode.Stretch)
            self.table.itemSelectionChanged.connect(self.update_buttons)
            group_layout.addWidget(self.table)
            buttons = QHBoxLayout()
            self.progress = QLabel()
            buttons.addWidget(self.progress, 1)
            self.refresh_button = QPushButton("Refresh")
            self.refresh_button.clicked.connect(self.refresh)
            buttons.addWidget(self.refresh_button)
            self.download_button = QPushButton("Download")
            self.download_button.setToolTip("Downloads and installs the selected catalog: it takes minutes, "
                                            "and the Siril console shows the progress.")
            self.download_button.clicked.connect(self.download)
            buttons.addWidget(self.download_button)
            group_layout.addLayout(buttons)
            layout.addWidget(group, 1)

            self.hints_check = QCheckBox("Use the position and scale of the image header as hints")
            self.hints_check.setToolTip("The search is blind anyway: a wrong position only costs a fraction of a second.")
            self.hints_check.setChecked(self.opts.use_hints)
            layout.addWidget(self.hints_check)
            self.objects_check = QCheckBox("List the deep sky objects of the field in the console")
            self.objects_check.setChecked(self.opts.list_objects)
            layout.addWidget(self.objects_check)

            bottom = QHBoxLayout()
            bottom.addStretch(1)
            self.solve_button = QPushButton("Solve")
            self.solve_button.setDefault(True)
            self.solve_button.clicked.connect(self.solve)
            bottom.addWidget(self.solve_button)
            close = QPushButton("Close")
            close.clicked.connect(self.close)
            bottom.addWidget(close)
            layout.addLayout(bottom)
            self.refresh()

        def save(self):
            self.opts.executable = self.exe_edit.text().strip()
            self.opts.use_hints = self.hints_check.isChecked()
            self.opts.list_objects = self.objects_check.isChecked()
            save_options(siril, self.opts)

        def executable_changed(self):
            if self.exe_edit.text().strip() != self.opts.executable:
                self.save()
                self.refresh()

        def browse(self):
            path, _ = QFileDialog.getOpenFileName(self, "Select the asterion executable")
            if path:
                self.exe_edit.setText(path)
                self.save()
                self.refresh()

        def refresh(self):
            """Asks the executable for its version and its catalogs: a wrong path only
            shows why nothing runs, the dialog stays usable."""
            self.catalogs = None
            self.table.setRowCount(0)
            try:
                self.exe, self.version = probe(self.exe_edit.text())
            except AsterionError as exc:
                self.exe = self.version = None
                self.status.setText(f"<span style=\"color:#c00000\">{exc}</span>")
                self.update_buttons()
                return
            try:
                self.catalogs = list_catalogs(self.exe, self.version)
            except AsterionError as exc:
                self.status.setText(f"{self.version}<br><span style=\"color:#c00000\">{exc}</span>")
                self.update_buttons()
                return
            installed = self.catalogs["installed"]
            note = " <b>No catalog is installed: download one.</b>" if not installed else ""
            self.status.setText(f"{self.version}, catalogs in {self.catalogs['directory']}.{note}")
            rows = [(c["name"], "installed",
                     f"{c['stars']:,} stars to magnitude {c['magnitudeLimit']:.1f}, {c['size'] / 1048576:.0f} MB"
                     if c.get("stars") else f"unreadable: {c.get('error', '')}", True) for c in installed]
            rows += [(c["name"], "available", c["description"], False) for c in self.catalogs["available"]
                     if not c["installed"]]
            self.table.setRowCount(len(rows))
            for i, (name, status, content, is_installed) in enumerate(rows):
                for j, text in enumerate((name, status, content)):
                    item = QTableWidgetItem(text)
                    item.setData(Qt.ItemDataRole.UserRole, (name, is_installed))
                    self.table.setItem(i, j, item)
            self.update_buttons()

        def selected_catalog(self):
            items = self.table.selectedItems()
            return items[0].data(Qt.ItemDataRole.UserRole) if items else None

        def update_buttons(self):
            busy = self.process is not None
            runs = self.exe is not None
            # when its catalogs can't be listed, an older asterion still solves with them
            installed = runs and (self.catalogs is None or len(self.catalogs["installed"]) > 0)
            selected = self.selected_catalog()
            self.download_button.setEnabled(self.catalogs is not None and not busy and selected is not None
                                            and not selected[1])
            self.refresh_button.setEnabled(not busy)
            self.solve_button.setEnabled(installed and not busy)
            self.solve_button.setToolTip("" if installed else
                                         "Asterion Solver doesn't run: check its path." if not runs else
                                         "Download a catalog first.")

        def download(self):
            selected = self.selected_catalog()
            if selected is None or selected[1] or self.process is not None:
                return
            name = selected[0]
            siril.log(f"Asterion: downloading {name}")
            self.progress.setText(f"Downloading {name}…")
            self.process = QProcess(self)
            self.process.setProcessChannelMode(QProcess.ProcessChannelMode.MergedChannels)
            self.process.readyReadStandardOutput.connect(self.download_output)
            self.process.finished.connect(lambda code, status: self.download_finished(name, code, status))
            self.process.errorOccurred.connect(lambda error: self.download_finished(name, -1, None))
            command = download_command(self.exe, name)
            self.process.start(command[0], command[1:])
            self.update_buttons()

        def download_output(self):
            if self.process is None:
                return
            text = bytes(self.process.readAllStandardOutput()).decode("utf-8", "replace")
            for line in text.splitlines():
                if line.strip():
                    siril.log("  " + line.rstrip())
                    self.progress.setText(line.strip()[:90])

        def download_finished(self, name, code, status):
            if self.process is None:
                return
            self.download_output()
            self.process.deleteLater()
            self.process = None
            if code == 0:
                siril.log(f"Asterion: {name} is installed", LogColor.GREEN)
                self.progress.setText(f"{name} is installed.")
            else:
                siril.log(f"Asterion: downloading {name} failed", LogColor.RED)
                self.progress.setText(f"Downloading {name} failed: see the console.")
            self.refresh()

        def solve(self):
            self.save()
            self.solve_button.setEnabled(False)
            QApplication.setOverrideCursor(Qt.CursorShape.WaitCursor)
            try:
                result = solve_loaded_image(siril, self.opts)
                if result is None:
                    self.progress.setText("Not solved: see the console.")
                else:
                    self.progress.setText(f"Solved: {result['ra']:.4f}°, {result['dec']:+.4f}°, "
                                          f"{result['scale']:.3f}\"/px.")
            except AsterionError as exc:
                siril.log(f"Asterion: {exc}", LogColor.RED)
                QMessageBox.critical(self, "Asterion Solver", str(exc))
            except Exception as exc:
                siril.log(f"Asterion: {exc}", LogColor.RED)
                QMessageBox.critical(self, "Asterion Solver", f"Solving failed: {exc}")
            finally:
                QApplication.restoreOverrideCursor()
                self.update_buttons()

        def closeEvent(self, event):
            if self.process is not None:
                self.process.kill()
                self.process.waitForFinished(2000)
            self.save()
            super().closeEvent(event)

    app = QApplication.instance() or QApplication(sys.argv)
    dialog = AsterionDialog()
    dialog.show()
    app.exec()


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------

def main():
    cli_argv = [a for a in sys.argv[1:] if a != "--"]
    siril = s.SirilInterface()
    try:
        siril.connect()
    except s.SirilConnectionError:
        print("Asterion: could not connect to Siril.", file=sys.stderr)
        sys.exit(1)
    try:
        siril.cmd("requires", "1.4.0")
    except Exception as exc:
        siril.log(f"Asterion: {exc}", LogColor.RED)
        siril.disconnect()
        sys.exit(1)
    if cli_argv or siril.is_cli():
        try:
            rc = run_cli(siril, cli_argv)
        finally:
            siril.disconnect()
        sys.exit(rc)
    try:
        run_gui(siril)
    finally:
        siril.disconnect()


if __name__ == "__main__":
    main()
