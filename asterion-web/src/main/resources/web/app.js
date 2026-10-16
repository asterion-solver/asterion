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
    row.append(element('td', running ? 'Installing…' : installed ? `Installed, ${size(installed.size)}` : 'Not installed'));
    const action = element('td');
    if (!running) {
        const button = element('button', installed ? 'Reinstall' : 'Install');
        button.addEventListener('click', () => install(name));
        action.append(button);
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
// Other browsers get the file input of the page, whose directory is up to them.
if (window.showOpenFilePicker) {
    drop.addEventListener('click', event => {
        event.preventDefault();
        pickImage();
    });
}

async function pickImage() {
    let handles;
    try {
        handles = await window.showOpenFilePicker({
            id: 'asterion-images',
            types: [{
                description: 'Images',
                accept: {
                    'image/fits': ['.fits', '.fit', '.fts', '.fz'],
                    'image/png': ['.png'],
                    'image/jpeg': ['.jpg', '.jpeg'],
                    'image/tiff': ['.tif', '.tiff'],
                    'image/bmp': ['.bmp'],
                },
            }],
        });
    } catch (e) {
        // the picker was closed without choosing a file
        return;
    }
    const transfer = new DataTransfer();
    transfer.items.add(await handles[0].getFile());
    $('file').files = transfer.files;
    showFile();
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
        result.replaceChildren(...(response.ok ? resultView(data) : [element('div', data.error, 'status error')]));
    } catch (e) {
        result.replaceChildren(element('div', String(e), 'status error'));
    } finally {
        button.disabled = false;
        button.textContent = 'Solve';
    }
});

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
        name.title = `${o.type}, ${o.distance.toFixed(2)}° from the center` + (o.majorArcmin ? `, ${formatAngle(o.majorArcmin / 60)} across` : '');
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

loadCatalogs();
