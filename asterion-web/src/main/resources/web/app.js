'use strict';

const $ = id => document.getElementById(id);

// --- formatting ---

function sexagesimal(value, hours) {
    const negative = value < 0;
    let v = Math.abs(hours ? value / 15 : value);
    const d = Math.floor(v);
    v = (v - d) * 60;
    const m = Math.floor(v);
    const s = (v - m) * 60;
    const pad = n => String(n).padStart(2, '0');
    return hours
        ? `${pad(d)}h ${pad(m)}m ${s.toFixed(2).padStart(5, '0')}s`
        : `${negative ? '-' : '+'}${pad(d)}° ${pad(m)}′ ${s.toFixed(1).padStart(4, '0')}″`;
}

function size(bytes) {
    return bytes >= 1e9 ? (bytes / 1e9).toFixed(1) + ' GB' : (bytes / 1e6).toFixed(0) + ' MB';
}

function element(tag, text, className) {
    const e = document.createElement(tag);
    if (text !== undefined) e.textContent = text;
    if (className) e.className = className;
    return e;
}

// --- catalogs ---

let polling = null;

async function loadCatalogs() {
    const response = await fetch('api/catalogs');
    const data = await response.json();
    $('version').textContent = data.version;
    $('directory').textContent = data.directory;
    const installed = new Map(data.installed.map(c => [c.name, c]));
    const running = new Set(data.installations.filter(i => i.running).map(i => i.name));
    const rows = $('catalog-rows');
    rows.replaceChildren();
    const names = new Set();
    for (const catalog of data.available) {
        names.add(catalog.name);
        rows.append(catalogRow(catalog.name, catalog.description, installed.get(catalog.name), running.has(catalog.name)));
    }
    for (const catalog of data.installed) {
        if (!names.has(catalog.name)) {
            rows.append(catalogRow(catalog.name, '', catalog, running.has(catalog.name)));
        }
    }
    gaiaVariants(data.available.map(c => c.name));
    const installations = $('installations');
    installations.replaceChildren(...data.installations.map(installationView));
    if (running.size > 0 && !polling) {
        polling = setInterval(loadCatalogs, 1000);
    } else if (running.size === 0 && polling) {
        clearInterval(polling);
        polling = null;
    }
}

// densities which are suggested besides the published ones, built from the Gaia servers
const OTHER_GAIA_DENSITIES = [250, 750, 1500, 3000, 5000];

function gaiaVariants(available) {
    const published = available.filter(name => name.startsWith('gaia-'));
    const options = [...published.map(name => [name, 'published, already indexed']),
        ...OTHER_GAIA_DENSITIES.map(n => `gaia-${n}`).filter(name => !published.includes(name)).map(name => [name, 'built from the Gaia servers'])];
    options.sort((a, b) => Number(a[0].slice(5)) - Number(b[0].slice(5)));
    $('gaia-variants').replaceChildren(...options.map(([name, label]) => {
        const option = element('option', label);
        option.value = name;
        return option;
    }));
}

function catalogRow(name, description, installed, running) {
    const row = element('tr');
    row.append(element('td', name), element('td', description, 'muted'));
    const status = running ? 'Installing…' : !installed ? 'Not installed'
        : `${installed.enabled ? 'Installed' : 'Disabled'}, ${size(installed.size)}`;
    row.append(element('td', status, installed && !installed.enabled ? 'muted' : undefined));
    const action = element('td', undefined, 'actions');
    if (!running) {
        const button = element('button', installed ? 'Reinstall' : 'Install');
        button.addEventListener('click', () => install(name));
        action.append(button);
        if (installed) {
            // a disabled catalog stays installed, but isn't used to solve
            const toggle = element('button', installed.enabled ? 'Disable' : 'Enable');
            toggle.title = installed.enabled ? 'Stop using this catalog to solve images, without removing it' : 'Use this catalog to solve images again';
            toggle.addEventListener('click', () => enable(name, !installed.enabled));
            action.append(' ', toggle);
        }
    }
    row.append(action);
    return row;
}

function installationView(installation) {
    const view = element('div', undefined, 'installation');
    const title = installation.running
        ? `Installing ${installation.name}: ${installation.step}`
        : installation.error ? `Installing ${installation.name} failed: ${installation.error}` : `${installation.name} is installed`;
    view.append(element('div', title, installation.error ? 'status error' : installation.running ? 'status' : 'status ok'));
    if (installation.running) {
        const progress = element('progress');
        if (installation.fraction !== null) {
            progress.value = installation.fraction;
        }
        view.append(progress);
    }
    if (installation.message) {
        view.append(element('div', installation.message, 'muted'));
    }
    return view;
}

async function enable(name, enabled) {
    const response = await fetch(`api/enable?name=${encodeURIComponent(name)}&enabled=${enabled}`, {method: 'POST'});
    if (!response.ok) {
        alert((await response.json()).error);
    }
    await loadCatalogs();
}

async function install(name) {
    const response = await fetch('api/install?name=' + encodeURIComponent(name), {method: 'POST'});
    if (!response.ok) {
        alert((await response.json()).error);
    }
    await loadCatalogs();
}

$('custom-form').addEventListener('submit', event => {
    event.preventDefault();
    install($('custom-name').value.trim());
});

// --- solving ---

const drop = $('drop');
['dragenter', 'dragover'].forEach(type => drop.addEventListener(type, event => {
    event.preventDefault();
    drop.classList.add('over');
}));
['dragleave', 'drop'].forEach(type => drop.addEventListener(type, () => drop.classList.remove('over')));
drop.addEventListener('drop', event => {
    event.preventDefault();
    $('file').files = event.dataTransfer.files;
    showFile();
});
$('file').addEventListener('change', showFile);

// Browsers which have the File System Access API (Chrome, Edge) remember the directory of the
// last image picked with this identifier, from one session to the next: the picker opens there.
// Other browsers get the file input of the page, whose directory is up to them. That API refuses
// some files, symbolic links for one: the page then falls back to the file input.
let usePicker = Boolean(window.showOpenFilePicker);
drop.addEventListener('click', event => {
    if (usePicker) {
        event.preventDefault();
        pickImage();
    }
});

async function pickImage() {
    try {
        const [handle] = await window.showOpenFilePicker({
            id: 'asterion-images',
            types: [{
                description: 'Images',
                accept: {
                    'image/fits': ['.fits', '.fit', '.fts', '.fz'],
                    'image/png': ['.png'],
                    'image/jpeg': ['.jpg', '.jpeg'],
                    'image/tiff': ['.tif', '.tiff'],
                    'image/bmp': ['.bmp'],
                    // the raw files of cameras, which have no common media type
                    'application/octet-stream': ['.cr2', '.cr3', '.nef', '.nrw', '.arw', '.srf', '.sr2', '.dng', '.pef', '.orf', '.rw2',
                        '.raf', '.srw', '.erf', '.3fr', '.kdc', '.dcr', '.mef', '.mos', '.rwl', '.iiq'],
                },
            }],
        });
        const transfer = new DataTransfer();
        transfer.items.add(await handle.getFile());
        $('file').files = transfer.files;
        showFile();
    } catch (e) {
        if (e.name === 'AbortError') {
            // the picker was closed without choosing a file
            return;
        }
        // a file the picker can't open, such as a link: the standard dialog can
        usePicker = false;
        $('drop-text').textContent = `The browser couldn't open that file (${e.message}): click again to use the standard dialog, which can`;
    }
}

/** Counts the images chosen: the answer to a solve is only shown while its image is still the chosen one. */
let selection = 0;

function showFile() {
    const file = $('file').files[0];
    $('drop-text').textContent = file ? `${file.name} (${size(file.size)})` : 'Drop an image here, or click to choose one';
    // the result on display is the one of the previous image, which would be taken for the one of this image
    selection++;
    const result = $('result');
    result.hidden = true;
    result.replaceChildren();
}

$('solve-form').addEventListener('submit', async event => {
    event.preventDefault();
    const file = $('file').files[0];
    if (!file) return;
    const form = new FormData(event.target);
    const query = new URLSearchParams({name: file.name});
    for (const [key, value] of form.entries()) {
        if (key === 'hints' || key === 'blind' || value === '') continue;
        query.set(key, value);
    }
    query.set('hints', event.target.hints.checked);
    query.set('blind', event.target.blind.checked);
    const button = $('solve-button');
    const result = $('result');
    const solved = selection;
    button.disabled = true;
    button.textContent = 'Solving…';
    result.hidden = false;
    result.replaceChildren(element('div', `Uploading and solving ${file.name}…`, 'muted'));
    try {
        const response = await fetch('api/solve?' + query, {method: 'POST', body: file});
        const data = await response.json();
        if (solved !== selection) return;
        const views = response.ok ? resultView(data) : [element('div', data.error, 'status error')];
        result.replaceChildren(...views);
        if (response.ok) {
            // the image on the sky view of a solution, or below the status, unless another image was submitted meanwhile
            picture(file, data).then(p => {
                if (!p || !result.contains(views[0])) return;
                if (data.solved) {
                    result.querySelectorAll('.sky-view').forEach(view => view.dispatchEvent(new CustomEvent('picture', {detail: p})));
                } else {
                    views[0].after(thumbnailView(p));
                }
            });
        }
    } catch (e) {
        if (solved !== selection) return;
        result.replaceChildren(e instanceof TypeError ? unreachableView() : element('div', String(e), 'status error'));
    } finally {
        button.disabled = false;
        button.textContent = 'Solve';
    }
});

/** The port the web interface listens on when it starts, as the page last knew it. */
let savedPort = null;

/**
 * The message for requests which get no answer at all: the browser only says "Failed to fetch".
 * The web interface was stopped, or it was restarted on the port saved in its options.
 */
function unreachableView() {
    const view = element('div', 'The web interface doesn\'t answer at this address: it was stopped, or restarted on another port. ', 'status error');
    if (savedPort && String(savedPort) !== location.port) {
        const address = `${location.protocol}//${location.hostname}:${savedPort}/`;
        const link = element('a', address);
        link.href = address;
        view.append('Its saved port is ' + savedPort + ': ', link);
    }
    return view;
}

/** The width of the pictures which the browser decodes, in pixels: the sky view zooms in on them. */
const PICTURE_WIDTH = 2048;
/** Larger files are not decoded by the browser: their decoding takes a lot of memory. */
const THUMBNAIL_MAX_BYTES = 64 * 1024 * 1024;

/**
 * The picture of a submitted image, which the sky view of its solution draws, or the page shows when it
 * isn't solved. It is made once the result is shown, rather than while the image is solved, as the server
 * often runs on the same machine, and the solution never contains it, so that the callers of the API don't
 * pay for it. The browser makes it from the file it uploaded when it can, and asks the server for it
 * otherwise, for FITS files for example.
 *
 * @return a promise of the picture, an image bitmap, whether its rows are bottom up, and a function which
 * gives a promise of the image at its size, or of null when the browser can't make it; or of null when
 * there's no picture
 */
async function picture(file, data) {
    const decoded = await browserPicture(file, data) || await serverPicture(data);
    if (!decoded) return null;
    const full = async () => await browserPicture(file, data, true) || await serverPicture(data, true);
    return {...decoded, full};
}

/** The thumbnail of an image which isn't solved. */
function thumbnailView({bitmap}) {
    const canvas = element('canvas');
    canvas.width = bitmap.width;
    canvas.height = bitmap.height;
    canvas.getContext('2d').drawImage(bitmap, 0, 0);
    bitmap.close();
    const figure = element('figure', undefined, 'thumbnail');
    figure.append(canvas);
    return figure;
}

/**
 * The image as decoded by the browser, when it can: JPEG, PNG... Browsers turn photos as their
 * EXIF tags say, which the solver doesn't: a thumbnail which doesn't have the shape of the solved
 * image isn't the image the solution describes, and the one of the server is used instead.
 *
 * @param full true for the image at its size, rather than a thumbnail
 */
async function browserPicture(file, data, full = false) {
    if (!file.type.startsWith('image/') || file.size > THUMBNAIL_MAX_BYTES) return null;
    let bitmap;
    try {
        bitmap = await createImageBitmap(file, full ? {} : {resizeWidth: PICTURE_WIDTH, resizeQuality: 'medium'});
    } catch {
        return null;
    }
    if (data.solved && Math.abs(bitmap.width / bitmap.height - data.fieldWidth / data.fieldHeight) > 0.02 * data.fieldWidth / data.fieldHeight) {
        bitmap.close();
        return null;
    }
    return {bitmap, bottomUp: false};
}

/**
 * The image as rendered by the server, which keeps the solved images: FITS files, TIFF files...
 * The server displays the first row of FITS files at the bottom, unless their header says
 * otherwise, and tells it.
 *
 * @param full true for the image at its size, rather than a thumbnail
 */
async function serverPicture(data, full = false) {
    const id = data.download && data.download.id;
    if (!id) return null;
    try {
        const response = await fetch(`api/thumbnail?id=${encodeURIComponent(id)}${full ? '&full=true' : ''}`);
        if (!response.ok) return null;
        const bottomUp = response.headers.get('Asterion-Row-Order') === 'bottom-up';
        const bitmap = await createImageBitmap(await response.blob());
        return {bitmap, bottomUp};
    } catch {
        return null;
    }
}

/** The name of an image without its extension, for the names of the files made from it. */
function baseName(image) {
    return image.replace(/\.[^.]*$/, '');
}

/** Saves a file made by the page, as a download. */
function save(blob, name) {
    const a = element('a');
    a.href = URL.createObjectURL(blob);
    a.download = name;
    a.click();
    URL.revokeObjectURL(a.href);
}

/**
 * The projection of a solution, from its WCS keywords: gnomonic, with the SIP polynomials of
 * the distortion. Pixels are counted from 0, like the positions of the objects of a solution.
 */
function wcsProjection(wcs) {
    const rad = Math.PI / 180;
    const ra0 = wcs.CRVAL1 * rad;
    const dec0 = wcs.CRVAL2 * rad;
    const [a, b, c, d] = [wcs.CD1_1, wcs.CD1_2, wcs.CD2_1, wcs.CD2_2];
    const det = a * d - b * c;
    const polynomial = (name) => {
        const order = wcs[`${name}_ORDER`] || 0;
        const terms = [];
        for (let i = 0; i <= order; i++) {
            for (let j = 0; i + j <= order; j++) {
                const k = wcs[`${name}_${i}_${j}`];
                if (k) terms.push([i, j, k]);
            }
        }
        return (u, v) => terms.reduce((sum, [i, j, k]) => sum + k * u ** i * v ** j, 0);
    };
    const [fa, fb, ap, bp] = ['A', 'B', 'AP', 'BP'].map(polynomial);
    return {
        /** The pixel of a position of the sky, or null when it's on the other side of the sky. */
        toPixel(raDeg, decDeg) {
            const ra = raDeg * rad, dec = decDeg * rad;
            const cosc = Math.sin(dec0) * Math.sin(dec) + Math.cos(dec0) * Math.cos(dec) * Math.cos(ra - ra0);
            if (cosc <= 0.01) return null;
            const xi = Math.cos(dec) * Math.sin(ra - ra0) / cosc / rad;
            const eta = (Math.cos(dec0) * Math.sin(dec) - Math.sin(dec0) * Math.cos(dec) * Math.cos(ra - ra0)) / cosc / rad;
            const u = (d * xi - b * eta) / det;
            const v = (-c * xi + a * eta) / det;
            return [u + ap(u, v) + wcs.CRPIX1 - 1, v + bp(u, v) + wcs.CRPIX2 - 1];
        },
        /** The position of the sky of a pixel, in degrees. */
        toSky(x, y) {
            const u0 = x + 1 - wcs.CRPIX1, v0 = y + 1 - wcs.CRPIX2;
            const u = u0 + fa(u0, v0), v = v0 + fb(u0, v0);
            const xi = (a * u + b * v) * rad, eta = (c * u + d * v) * rad;
            const rho = Math.hypot(xi, eta);
            const cc = Math.atan(rho);
            const dec = rho === 0 ? dec0 : Math.asin(Math.cos(cc) * Math.sin(dec0) + eta * Math.sin(cc) * Math.cos(dec0) / rho);
            const ra = ra0 + Math.atan2(xi * Math.sin(cc), rho * Math.cos(dec0) * Math.cos(cc) - eta * Math.sin(dec0) * Math.sin(cc));
            return [((ra / rad) % 360 + 360) % 360, dec / rad];
        },
    };
}

/** Tells if two boxes, [left, top, right, bottom], overlap. */
function intersects(p, q) {
    return p[0] < q[2] && q[0] < p[2] && p[1] < q[3] && q[1] < p[3];
}

/** The steps of the grid of coordinates, in degrees. */
const GRID_STEPS = [1, 2, 5, 10, 15, 20, 30].map(m => m / 60).concat([1, 2, 5, 10, 15, 20, 30]);

/** A declination of the grid: "+33°", "+33° 10′". */
function dmsLabel(dec, step) {
    const sign = dec < -1e-9 ? '−' : '+';
    const minutes = Math.round(Math.abs(dec) * 60);
    const d = Math.floor(minutes / 60), m = minutes % 60;
    return step >= 1 || m === 0 ? `${sign}${d}°` : `${sign}${d}° ${String(m).padStart(2, '0')}′`;
}

/** A right ascension of the grid: "18h 53m", "18h 53m 20s". */
function hmsLabel(ra, step) {
    const seconds = Math.round(ra / 15 * 3600);
    const h = Math.floor(seconds / 3600) % 24, m = Math.floor(seconds % 3600 / 60), s = seconds % 60;
    const pad = n => String(n).padStart(2, '0');
    return step >= 1 / 4 && s === 0 ? `${h}h ${pad(m)}m` : `${h}h ${pad(m)}m ${pad(s)}s`;
}

function resultView(data) {
    const views = [];
    const time = `${data.timing.totalMillis.toFixed(0)} ms (read ${data.timing.readMillis.toFixed(0)}, detect ${data.timing.detectMillis.toFixed(0)}, solve ${data.timing.solveMillis.toFixed(0)})`;
    if (!data.solved) {
        views.push(element('div', `No solution found in ${time}, with ${data.stars} stars`, 'status error'));
        return views;
    }
    views.push(element('div', `Solved in ${time}`, 'status ok'));
    views.push(skyView(data));
    views.push(objectsView(data.objects || []));
    const metrics = element('div', undefined, 'metrics');
    const metric = (label, value) => {
        const m = element('div', value);
        m.prepend(element('span', label));
        metrics.append(m);
    };
    metric('Constellation', data.constellation.name);
    metric('Right ascension', `${sexagesimal(data.ra, true)} (${data.ra.toFixed(5)}°)`);
    metric('Declination', `${sexagesimal(data.dec, false)} (${data.dec.toFixed(5)}°)`);
    metric('Scale', `${data.scale.toFixed(4)} ″/px`);
    metric('Field', `${data.fieldWidth.toFixed(3)}° × ${data.fieldHeight.toFixed(3)}°`);
    metric('Rotation', `${data.rotation.toFixed(3)}° east of north${data.flipped ? ', flipped' : ''}`);
    if (data.focalLength !== undefined) {
        metric('Focal length', `${data.focalLength.toFixed(0)} mm (${data.pixelSize.toFixed(2)} µm pixels)`);
    }
    metric('Stars', `${data.matchedStars} matched out of ${data.stars}`);
    metric('Residuals', `${data.rmsArcsec.toFixed(2)}″ RMS, FWHM ${data.fwhmArcsec.toFixed(1)}″`);
    metric('Catalog', data.index);
    views.push(metrics);
    views.push(chartsView(data));
    const table = element('table', undefined, 'wcs');
    for (const [key, value] of Object.entries(data.wcs)) {
        const row = element('tr');
        row.append(element('td', key), element('td', String(value)));
        table.append(row);
    }
    const details = element('details');
    details.append(element('summary', 'WCS keywords'), table);
    views.push(details);
    views.push(downloadsView(data));
    return views;
}

/** The deep sky objects of the image: "Contains M 31 (Andromeda Galaxy), M 110, M 32 and 4 more objects". */
function objectsView(objects) {
    const view = element('div', undefined, 'objects');
    if (objects.length === 0) {
        view.append(element('span', 'No catalogued deep sky object in the field', 'muted'));
        return view;
    }
    const shown = objects.slice(0, 5);
    view.append(element('span', 'Contains '));
    shown.forEach((o, i) => {
        const name = element('span', o.label, 'object');
        name.title = `${o.type}, ${o.distance.toFixed(2)}° from the center` + (o.majorArcmin ? `, ${formatAngle(o.majorArcmin / 60)} across` : '')
            + (o.aliases ? `; also ${o.aliases}` : '');
        view.append(i > 0 ? ', ' : '', name);
    });
    if (objects.length > shown.length) {
        const more = element('span', ` and ${objects.length - shown.length} more`, 'muted');
        more.title = objects.slice(shown.length).map(o => o.label).join(', ');
        view.append(more);
    }
    return view;
}

/** The files of a solution: the image with its solution, its header, the matched stars, the result. */
function downloadsView(data) {
    const view = element('div', undefined, 'downloads');
    view.append(element('span', 'Download', 'muted'));
    const download = data.download || {};
    const link = (format, label, title) => {
        const a = element('a', label, 'button');
        a.href = `api/download?id=${encodeURIComponent(download.id)}&format=${format}`;
        a.title = title;
        a.setAttribute('download', '');
        view.append(a);
    };
    if (download.fits) {
        link('fits', 'Solved FITS', 'The image, with the solution in its header');
    }
    if (download.id) {
        link('wcs', 'WCS header (.wcs)', 'The solution as a FITS header without data, like the .wcs files of astrometry.net');
        link('csv', 'Matched stars (.csv)', 'The stars of the image which were matched with the catalog: pixel and sky positions, residuals');
    }
    const json = element('button', 'Result (.json)');
    json.title = 'This result, as the JSON output of the command line tool';
    json.addEventListener('click', () => save(new Blob([JSON.stringify(data, null, 2)], {type: 'application/json'}), baseName(data.image) + '.json'));
    view.append(json);
    if (download.id && !download.fits) {
        view.append(element('div', 'The image isn\'t a FITS file: its solution can be downloaded as a FITS header.', 'muted'));
    }
    return view;
}

// --- the ASTAP compatible executable, when this is a native executable next to the command line one ---

async function loadAstap() {
    const data = await (await fetch('api/astap')).json();
    // only a native executable can install anything: the card says why it can't, when it can't
    $('astap').hidden = !data.native;
    $('astap-install').hidden = !data.available;
    if (!data.available) {
        $('astap-state').replaceChildren(element('div', data.reason, 'status error'));
        return;
    }
    $('astap-name').textContent = data.targetName;
    $('astap-cli').textContent = data.executableName;
    $('astap-directory').textContent = data.directory;
    $('astap-state').replaceChildren(element('div', data.installed
        ? `Installed: ${data.target}${data.link ? ' (a link to ' + data.executableName + ')' : ''}`
        : 'Not installed', data.installed ? 'status ok' : 'status'));
    $('astap-install').textContent = (data.installed ? 'Reinstall ' : 'Install ') + data.targetName;
}

$('astap-install').addEventListener('click', async () => {
    const response = await fetch('api/astap', {method: 'POST'});
    if (!response.ok) {
        alert((await response.json()).error);
    }
    await loadAstap();
});

// --- the port of the web interface, saved for the next time it starts ---

async function loadSettings() {
    const response = await fetch('api/settings');
    if (!response.ok) return;
    const settings = await response.json();
    savedPort = settings.port;
    $('port').value = settings.port;
    showSettings(settings);
}

function showSettings(settings, saved) {
    const state = $('port-state');
    state.classList.remove('error');
    const address = port => `${location.protocol}//${location.hostname}:${port}/`;
    if (settings.portFromCommandLine) {
        state.textContent = (saved ? `Saved: port ${settings.port} will be used when the web interface starts without the --port option. ` : '')
            + `It was started with --port ${settings.currentPort}, which takes precedence.`;
    } else if (settings.restartNeeded) {
        state.textContent = `Saved: restart the web interface to use port ${settings.port}. It will then be at ${address(settings.port)}`;
    } else {
        state.textContent = saved ? `Saved: the web interface already uses port ${settings.port}.` : '';
    }
}

// the field is in the form of the image: Enter saves the port, rather than solving the image
$('port').addEventListener('keydown', event => {
    if (event.key === 'Enter') {
        event.preventDefault();
        $('port-save').click();
    }
});

$('port-save').addEventListener('click', async () => {
    const response = await fetch('api/settings?port=' + encodeURIComponent($('port').value), {method: 'POST'});
    const data = await response.json();
    if (response.ok) {
        savedPort = data.port;
        showSettings(data, true);
    } else {
        $('port-state').textContent = data.error;
        $('port-state').classList.add('error');
    }
});

loadCatalogs();
loadAstap();
loadSettings();
