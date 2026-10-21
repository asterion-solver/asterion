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

function showFile() {
    const file = $('file').files[0];
    $('drop-text').textContent = file ? `${file.name} (${size(file.size)})` : 'Drop an image here, or click to choose one';
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
    button.disabled = true;
    button.textContent = 'Solving…';
    result.hidden = false;
    result.replaceChildren(element('div', `Uploading and solving ${file.name}…`, 'muted'));
    try {
        const response = await fetch('api/solve?' + query, {method: 'POST', body: file});
        const data = await response.json();
        const views = response.ok ? resultView(data) : [element('div', data.error, 'status error')];
        result.replaceChildren(...views);
        if (response.ok) {
            // below the status, unless another image was submitted meanwhile
            thumbnailView(file, data).then(view => view && result.contains(views[0]) && views[0].after(view));
        }
    } catch (e) {
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

/** The width of thumbnails, in pixels: they are shown smaller, sharp on high density screens. */
const THUMBNAIL_WIDTH = 800;
/** Larger files are not decoded by the browser: their decoding takes a lot of memory. */
const THUMBNAIL_MAX_BYTES = 64 * 1024 * 1024;

/**
 * A thumbnail of a submitted image, with an overlay of the solution: the coordinates, the
 * orientation and the deep sky objects. It is made once the result is shown, rather than while the
 * image is solved, as the server often runs on the same machine, and the solution never contains
 * it, so that the callers of the API don't pay for it. The browser makes it from the file it
 * uploaded when it can, and asks the server for it otherwise, for FITS files for example.
 *
 * @return a promise of the view, or of null when there's no thumbnail
 */
async function thumbnailView(file, data) {
    const picture = await browserPicture(file, data) || await serverPicture(data);
    if (!picture) return null;
    const {bitmap, bottomUp} = picture;
    const canvas = element('canvas');
    canvas.width = bitmap.width;
    canvas.height = bitmap.height;
    canvas.getContext('2d').drawImage(bitmap, 0, 0);
    bitmap.close();
    const frame = element('div', undefined, 'frame');
    frame.append(canvas);
    const figure = element('figure', undefined, 'thumbnail');
    figure.append(frame);
    if (data.solved) {
        frame.append(overlay(data, canvas.width, canvas.height, bottomUp));
        // hovering shows the overlay, and clicking keeps it, which touch screens need
        frame.addEventListener('click', () => frame.classList.toggle('pinned'));
        figure.append(element('figcaption', 'Hover over the image, or click it, to show its coordinates, its orientation and its objects'));
    }
    return figure;
}

/**
 * The image as decoded by the browser, when it can: JPEG, PNG... Browsers turn photos as their
 * EXIF tags say, which the solver doesn't: a thumbnail which doesn't have the shape of the solved
 * image isn't the image the solution describes, and the one of the server is used instead.
 */
async function browserPicture(file, data) {
    if (!file.type.startsWith('image/') || file.size > THUMBNAIL_MAX_BYTES) return null;
    let bitmap;
    try {
        bitmap = await createImageBitmap(file, {resizeWidth: THUMBNAIL_WIDTH, resizeQuality: 'medium'});
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
 */
async function serverPicture(data) {
    const id = data.download && data.download.id;
    if (!id) return null;
    try {
        const response = await fetch(`api/thumbnail?id=${encodeURIComponent(id)}`);
        if (!response.ok) return null;
        const bottomUp = response.headers.get('Asterion-Row-Order') === 'bottom-up';
        const bitmap = await createImageBitmap(await response.blob());
        return {bitmap, bottomUp};
    } catch {
        return null;
    }
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

/** The radius of the outlines whose labels are inside, in pixels of the thumbnail: they would hide smaller objects. */
const LABELS_INSIDE = 80;
/** The objects of the catalogs of Lynds which an overlay shows, at most. */
const MAX_LYNDS_OBJECTS = 6;

/** The steps of the grid of coordinates, in degrees. */
const GRID_STEPS = [1, 2, 5, 10, 15, 20, 30].map(m => m / 60).concat([1, 2, 5, 10, 15, 20, 30]);

/**
 * The overlay of a thumbnail, in the pixels of the thumbnail: the grid of coordinates, a compass
 * which shows north and east, and the outlines of the deep sky objects.
 */
function overlay(data, thumbWidth, thumbHeight, bottomUp) {
    const ns = 'http://www.w3.org/2000/svg';
    const svg = document.createElementNS(ns, 'svg');
    svg.setAttribute('class', 'overlay');
    svg.setAttribute('viewBox', `0 0 ${thumbWidth} ${thumbHeight}`);
    const add = (tag, attributes, text) => {
        const e = document.createElementNS(ns, tag);
        for (const [k, v] of Object.entries(attributes)) e.setAttribute(k, v);
        if (text !== undefined) e.textContent = text;
        svg.append(e);
        return e;
    };
    const projection = wcsProjection(data.wcs);
    const width = Math.round(data.fieldWidth * 3600 / data.scale);
    const height = Math.round(data.fieldHeight * 3600 / data.scale);
    const k = thumbWidth / width;
    // from the pixels of the image to the ones of the thumbnail
    const toThumb = ([x, y]) => [(x + 0.5) * k, bottomUp ? (height - y - 0.5) * k : (y + 0.5) * k];
    const project = (ra, dec) => {
        const p = projection.toPixel(ra, dec);
        return p && toThumb(p);
    };
    const inside = p => p && p[0] >= 0 && p[1] >= 0 && p[0] <= thumbWidth && p[1] <= thumbHeight;
    const path = points => {
        let d = '', pen = false;
        for (const p of points) {
            if (!p) {
                pen = false;
                continue;
            }
            d += `${pen ? 'L' : 'M'}${p[0].toFixed(1)},${p[1].toFixed(1)}`;
            pen = true;
        }
        return d;
    };

    // the extent of the field on the sky, the right ascensions relative to the one of the center
    const [raCenter, decCenter] = projection.toSky((width - 1) / 2, (height - 1) / 2);
    let decMin = 90, decMax = -90, raMin = 180, raMax = -180;
    for (let i = 0; i <= 8; i++) {
        for (let j = 0; j <= 8; j++) {
            const [ra, dec] = projection.toSky(i * (width - 1) / 8, j * (height - 1) / 8);
            const dra = ((ra - raCenter + 540) % 360) - 180;
            decMin = Math.min(decMin, dec);
            decMax = Math.max(decMax, dec);
            raMin = Math.min(raMin, dra);
            raMax = Math.max(raMax, dra);
        }
    }
    for (const pole of [90, -90]) {
        if (inside(project(0, pole))) {
            if (pole > 0) {
                decMax = 90;
            } else {
                decMin = -90;
            }
            raMin = -180;
            raMax = 180;
        }
    }
    const diagonal = Math.hypot(data.fieldWidth, data.fieldHeight);
    const nice = target => GRID_STEPS.find(s => s >= target) || 30;
    const decStep = nice(diagonal / 5);
    const raStep = nice(decStep / Math.max(0.1, Math.cos(decCenter * Math.PI / 180)));
    const samples = 96;
    // the corner of the compass, which the labels leave free
    const compass = [thumbWidth - 120, 0, thumbWidth, 120];
    const clear = box => !(box[0] < compass[2] && compass[0] < box[2] && box[1] < compass[3] && compass[1] < box[3]);
    // the label of a line, where it crosses an edge of the image: declinations on the left,
    // right ascensions at the bottom, or else on the opposite edge
    const label = (points, edges, text) => {
        const crossings = [];
        for (let i = 1; i < points.length; i++) {
            const [p, q] = [points[i - 1], points[i]];
            if (!p || !q || inside(p) === inside(q)) continue;
            const [x, y] = inside(p) ? p : q;
            const distances = {left: x, right: thumbWidth - x, top: y, bottom: thumbHeight - y};
            crossings.push([x, y, Object.keys(distances).reduce((a, b) => distances[a] <= distances[b] ? a : b)]);
        }
        const crossing = edges.map(edge => crossings.find(c => c[2] === edge)).find(c => c);
        if (!crossing) return;
        const [x, y, edge] = crossing;
        // the width of the label, about: the font is 13 pixels high
        const width = text.length * 7;
        const along = Math.min(Math.max(x + 4, 4), thumbWidth - 4 - width);
        const at = {
            left: {x: 4, y: y - 4},
            right: {x: thumbWidth - 4 - width, y: y - 4},
            bottom: {x: along, y: thumbHeight - 6},
            top: {x: along, y: 16},
        }[edge];
        if (clear([at.x, at.y - 12, at.x + width, at.y + 2])) {
            add('text', {...at, class: 'grid-label'}, text);
        }
    };
    const lines = [];
    for (let dec = Math.ceil(decMin / decStep) * decStep; dec <= decMax; dec += decStep) {
        const points = [];
        for (let i = 0; i <= samples; i++) points.push(project(raCenter + raMin + (raMax - raMin) * i / samples, dec));
        lines.push([points, ['left', 'right'], dmsLabel(dec, decStep)]);
    }
    for (let dra = Math.ceil((raCenter + raMin) / raStep) * raStep - raCenter; dra <= raMax; dra += raStep) {
        const points = [];
        for (let i = 0; i <= samples; i++) points.push(project(raCenter + dra, decMin + (decMax - decMin) * i / samples));
        lines.push([points, ['bottom', 'top'], hmsLabel(((raCenter + dra) % 360 + 360) % 360, raStep)]);
    }
    add('path', {d: lines.map(([points]) => path(points)).join(''), class: 'grid'});
    for (const line of lines) label(...line);

    // the deep sky objects, the most prominent first, as they come: their outlines, from their sizes
    // and position angles, with their labels. An object is shown only where its label has room,
    // covering neither other labels nor markers: an outline without a name is noise
    const taken = [compass];
    const overlaps = box => taken.some(t => box[0] < t[2] && t[0] < box[2] && box[1] < t[3] && t[1] < box[3]);
    const free = box => box[0] >= 0 && box[2] <= thumbWidth && box[1] >= 0 && box[3] <= thumbHeight && !overlaps(box);
    let lynds = 0;
    // the large outlines which are drawn: centers and radii
    const outlines = [];
    for (const o of (data.objects || []).slice(0, 60)) {
        // the catalogs of Lynds cut complexes into many pieces, with generous extents: a few are enough
        const fromLynds = /^L[BD]N /.test(o.name);
        if (fromLynds && lynds >= MAX_LYNDS_OBJECTS) continue;
        // the position computed by the server, which is exact: the inverse polynomials of the
        // distortion are approximations, which drift on the edges of very wide fields
        const center = toThumb([o.x, o.y]);
        const projected = project(o.ra, o.dec);
        if (!inside(center) || !projected) continue;
        const shift = p => p && [p[0] + center[0] - projected[0], p[1] + center[1] - projected[1]];
        // an object whose orientation isn't known is a circle of the same area: an ellipse would have to
        // point somewhere, and a circle of its major axis would be much larger than the object
        const oriented = o.angle !== undefined && o.angle !== null;
        const major = (o.majorArcmin || 0) / 120;
        const minor = (o.minorArcmin || o.majorArcmin || 0) / 120;
        const a = oriented ? major : Math.sqrt(major * minor);
        const b = oriented ? minor : a;
        const angle = oriented ? o.angle * Math.PI / 180 : 0;
        const outline = [];
        for (let i = 0; i <= 48 && a > 0; i++) {
            const t = 2 * Math.PI * i / 48;
            const north = a * Math.cos(t) * Math.cos(angle) - b * Math.sin(t) * Math.sin(angle);
            const east = a * Math.cos(t) * Math.sin(angle) + b * Math.sin(t) * Math.cos(angle);
            outline.push(shift(project(o.ra + east / Math.cos(o.dec * Math.PI / 180), o.dec + north)));
        }
        const extent = outline.filter(p => p).reduce((m, p) => Math.max(m, Math.hypot(p[0] - center[0], p[1] - center[1])), 0);
        // a small object is its marker, which must be free; the outline of a large one can have labels inside
        const radius = Math.max(extent, 6);
        // the catalogs of Lynds cut complexes into pieces, with generous extents: the large outlines of
        // the pieces of a complex whose outline is drawn already, or of the same one, would pile up
        if (fromLynds && radius >= LABELS_INSIDE
            && outlines.some(([c, r]) => Math.hypot(c[0] - center[0], c[1] - center[1]) < r + radius)) continue;
        const marker = [center[0] - radius, center[1] - radius, center[0] + radius, center[1] + radius];
        if (radius < LABELS_INSIDE && overlaps(marker)) continue;
        // inside the outlines which are large enough, as a label next to a large outline is next to
        // other objects too; next to the marker otherwise
        const width = o.label.length * 8.5;
        const at = (x, y) => [x, y - 13, x + width, y + 4];
        const candidates = radius >= LABELS_INSIDE
            ? [at(center[0] - width / 2, center[1] + 5), at(center[0] - width / 2, center[1] + 24), at(center[0] - width / 2, center[1] - 14)]
            : [at(center[0] + radius + 4, center[1] + 5), at(center[0] - radius - 4 - width, center[1] + 5),
                at(center[0] - width / 2, center[1] + radius + 17), at(center[0] - width / 2, center[1] - radius - 6)];
        const box = candidates.find(free);
        if (!box) continue;
        if (extent >= 6) {
            add('path', {d: path(outline) + 'Z', class: 'object'});
        } else {
            add('circle', {cx: center[0], cy: center[1], r: 6, class: 'object'});
        }
        add('text', {x: box[0], y: box[3] - 4, class: 'object-label'}, o.label);
        taken.push(box);
        if (radius >= LABELS_INSIDE) {
            outlines.push([center, radius]);
        }
        if (radius < LABELS_INSIDE) {
            taken.push(marker);
        }
        if (fromLynds) {
            lynds++;
        }
    }

    // the compass, in the corner without labels: the directions of north and east at the center of the image
    const step = diagonal / 20;
    const origin = project(raCenter, decCenter);
    const north = project(raCenter, Math.min(90, decCenter + step));
    const east = project(raCenter + step / Math.max(0.01, Math.cos(decCenter * Math.PI / 180)), decCenter);
    if (origin && north && east) {
        const corner = [(compass[0] + compass[2]) / 2, (compass[1] + compass[3]) / 2];
        const arrow = (to, name) => {
            const dx = to[0] - origin[0], dy = to[1] - origin[1];
            const length = Math.hypot(dx, dy) || 1;
            const tip = [corner[0] + 32 * dx / length, corner[1] + 32 * dy / length];
            add('line', {x1: corner[0], y1: corner[1], x2: tip[0], y2: tip[1], class: 'compass'});
            add('text', {x: corner[0] + 44 * dx / length, y: corner[1] + 44 * dy / length + 5, class: 'compass-label'}, name);
        };
        arrow(north, 'N');
        arrow(east, 'E');
    }
    return svg;
}

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
    json.addEventListener('click', () => {
        const a = element('a');
        a.href = URL.createObjectURL(new Blob([JSON.stringify(data, null, 2)], {type: 'application/json'}));
        a.download = data.image.replace(/\.[^.]*$/, '') + '.json';
        a.click();
        URL.revokeObjectURL(a.href);
    });
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
