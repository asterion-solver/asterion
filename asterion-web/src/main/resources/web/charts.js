'use strict';

// Charts of a solution: where it is among the constellations, and the stars of the catalog around
// it. Both are stereographic projections centered on the solution, north up and east left, as the
// sky is seen from the ground.

const RAD = Math.PI / 180;
const SIZE = 600;
const SVG = 'http://www.w3.org/2000/svg';
// the largest radius of the star chart, in degrees: wider fields are seen on the constellation chart
const MAX_FIELD_CHART_RADIUS = 30;

let skyData = null;

function sky() {
    if (!skyData) {
        skyData = fetch('sky.json').then(response => {
            if (!response.ok) throw new Error('Cannot load the sky data');
            return response.json();
        });
    }
    return skyData;
}

function vector(ra, dec) {
    return [Math.cos(dec * RAD) * Math.cos(ra * RAD), Math.cos(dec * RAD) * Math.sin(ra * RAD), Math.sin(dec * RAD)];
}

function angle(a, b) {
    return Math.acos(Math.min(1, a[0] * b[0] + a[1] * b[1] + a[2] * b[2])) / RAD;
}

/** A stereographic projection of the sky in a disc of the given radius, in degrees. */
class Projection {
    constructor(ra, dec, radius) {
        this.ra = ra * RAD;
        this.sinDec = Math.sin(dec * RAD);
        this.cosDec = Math.cos(dec * RAD);
        this.radius = radius;
        this.scale = (SIZE / 2 - 4) / (2 * Math.tan(radius * RAD / 2));
    }

    /** Projects a position, or returns null when it's far behind the edge of the chart. */
    project(ra, dec) {
        const d = ra * RAD - this.ra;
        const sinDec = Math.sin(dec * RAD);
        const cosDec = Math.cos(dec * RAD);
        const cosC = this.sinDec * sinDec + this.cosDec * cosDec * Math.cos(d);
        if (cosC < Math.cos(Math.min(170, 1.6 * this.radius) * RAD)) return null;
        const k = this.scale * 2 / (1 + cosC);
        return [SIZE / 2 - k * cosDec * Math.sin(d), SIZE / 2 - k * (this.cosDec * sinDec - this.sinDec * cosDec * Math.cos(d))];
    }

    /**
     * An SVG path through positions given as a flat list of (ra, dec): segments are cut along great
     * circles, which projections curve, when they pass near the chart.
     */
    path(points, closed, step = 2) {
        step = Math.min(step, this.radius / 8);
        const center = vector(this.ra / RAD, Math.asin(this.sinDec) / RAD);
        const limit = 1.6 * this.radius;
        let path = '';
        let previous = null;
        const move = (ra, dec) => {
            const p = this.project(ra, dec);
            if (p === null) {
                previous = null;
                return;
            }
            path += (previous === null ? 'M' : 'L') + p[0].toFixed(1) + ' ' + p[1].toFixed(1);
            previous = p;
        };
        const count = points.length / 2;
        let a = null;
        for (let i = 0; i < count + (closed ? 1 : 0); i++) {
            const ra = points[2 * (i % count)];
            const dec = points[2 * (i % count) + 1];
            const b = vector(ra, dec);
            if (a !== null) {
                const length = angle(a, b);
                // no point of the segment is closer to the center than this
                if ((angle(a, center) + angle(b, center) - length) / 2 < limit) {
                    const n = Math.min(2000, Math.ceil(length / step));
                    for (let j = 1; j < n; j++) {
                        const t = j / n;
                        const v = [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t];
                        move(Math.atan2(v[1], v[0]) / RAD, Math.atan2(v[2], Math.hypot(v[0], v[1])) / RAD);
                    }
                }
            }
            move(ra, dec);
            a = b;
        }
        return path + (closed && path ? 'Z' : '');
    }
}

function svgElement(tag, attributes, text) {
    const e = document.createElementNS(SVG, tag);
    for (const [name, value] of Object.entries(attributes)) e.setAttribute(name, value);
    if (text !== undefined) e.textContent = text;
    return e;
}

/** An empty chart: a disc of sky, which clips what's drawn in it. */
function chart(id) {
    const svg = svgElement('svg', {viewBox: `0 0 ${SIZE} ${SIZE}`, class: 'sky', role: 'img'});
    const clip = svgElement('clipPath', {id});
    clip.append(svgElement('circle', {cx: SIZE / 2, cy: SIZE / 2, r: SIZE / 2 - 4}));
    svg.append(clip, svgElement('circle', {cx: SIZE / 2, cy: SIZE / 2, r: SIZE / 2 - 4, class: 'sky-bg'}));
    const content = svgElement('g', {'clip-path': `url(#${id})`});
    svg.append(content);
    return {svg, content};
}

function drawGrid(content, projection) {
    const lines = [];
    // a coarser grid when the chart is large
    const step = projection.radius > 15 ? 15 : projection.radius > 4 ? 5 : projection.radius > 1 ? 1 : 0.25;
    // meridians about as far apart as parallels, in the middle of the chart
    const raStep = [0.25, 0.5, 1, 2, 5, 10, 15, 30, 45, 90].find(s => s >= step / Math.max(projection.cosDec, 0.01)) || 90;
    const center = Math.asin(projection.sinDec) / RAD;
    for (let dec = -90 + step; dec < 90; dec += step) {
        if (Math.abs(dec - center) > 1.6 * projection.radius + step) continue;
        const points = [];
        for (let ra = 0; ra < 360; ra += 1) points.push(ra, dec);
        lines.push(projection.path(points, true, 1));
    }
    for (let ra = 0; ra < 360; ra += raStep) {
        lines.push(projection.path([ra, -89.9, ra, 0, ra, 89.9], false, 1));
    }
    content.append(svgElement('path', {d: lines.join(''), class: 'sky-grid'}));
}

function drawConstellations(content, projection, data, current, labels) {
    const boundaries = data.boundaries.map(b => projection.path(b.slice(1), false, 1));
    content.append(svgElement('path', {d: boundaries.join(''), class: 'sky-boundary'}));
    const own = data.boundaries.filter(b => b[0] === current).map(b => projection.path(b.slice(1), false, 1));
    content.append(svgElement('path', {d: own.join(''), class: 'sky-boundary current'}));
    content.append(svgElement('path', {d: data.lines.map(l => projection.path(l.slice(1), false)).join(''), class: 'sky-figure'}));
    if (!labels) return;
    // names at the middle of the stars of their figures
    const centers = new Map();
    for (const line of data.lines) {
        const sum = centers.get(line[0]) || [0, 0, 0];
        for (let i = 1; i < line.length; i += 2) {
            const v = vector(line[i], line[i + 1]);
            sum[0] += v[0];
            sum[1] += v[1];
            sum[2] += v[2];
        }
        centers.set(line[0], sum);
    }
    for (const [abbreviation, v] of centers) {
        const p = projection.project(Math.atan2(v[1], v[0]) / RAD, Math.atan2(v[2], Math.hypot(v[0], v[1])) / RAD);
        if (p === null || Math.hypot(p[0] - SIZE / 2, p[1] - SIZE / 2) > SIZE / 2 - 40) continue;
        content.append(svgElement('text', {x: p[0], y: p[1], class: abbreviation === current ? 'sky-label current' : 'sky-label'},
            data.names[abbreviation]));
    }
}

/** The outline of the image, or a marker when it would be too small to see. */
function drawField(content, projection, outline) {
    const flat = outline.flat();
    const projected = outline.map(([ra, dec]) => projection.project(ra, dec)).filter(p => p !== null);
    const xs = projected.map(p => p[0]);
    const ys = projected.map(p => p[1]);
    const extent = Math.max(Math.max(...xs) - Math.min(...xs), Math.max(...ys) - Math.min(...ys));
    content.append(svgElement('path', {d: projection.path(flat, true, 0.5), class: 'sky-field'}));
    if (extent < 12) {
        const x = xs.reduce((a, b) => a + b) / xs.length;
        const y = ys.reduce((a, b) => a + b) / ys.length;
        content.append(svgElement('circle', {cx: x, cy: y, r: 11, class: 'sky-marker'}));
        content.append(svgElement('path', {d: `M${x - 20} ${y}h8m16 0h8M${x} ${y - 20}v8m0 16v8`, class: 'sky-marker'}));
    }
}

/** The deep sky objects of the image: an ellipse of their size, and their name. */
function drawObjects(content, projection, objects) {
    for (const o of objects.slice(0, 12)) {
        const p = projection.project(o.ra, o.dec);
        if (p === null) continue;
        // the ellipse of the object, in pixels of the chart: the scale is the one at the center
        const pixelsPerDegree = projection.scale * 2 * Math.tan(0.5 * RAD);
        const major = Math.max(6, (o.majorArcmin || 2) / 60 * pixelsPerDegree / 2);
        const minor = Math.max(6, (o.minorArcmin || o.majorArcmin || 2) / 60 * pixelsPerDegree / 2);
        // position angles are east of north, and east is on the left
        const angle = o.angle === null || o.angle === undefined ? 0 : -o.angle;
        content.append(svgElement('ellipse', {cx: p[0], cy: p[1], rx: major, ry: minor, transform: `rotate(${angle} ${p[0]} ${p[1]})`, class: 'sky-object'}));
        content.append(svgElement('text', {x: p[0], y: p[1] - minor - 4, class: 'sky-object-label'}, o.name));
    }
}

function drawCompass(svg) {
    svg.append(svgElement('text', {x: SIZE / 2, y: 22, class: 'sky-compass'}, 'N'));
    svg.append(svgElement('text', {x: 16, y: SIZE / 2 + 5, class: 'sky-compass'}, 'E'));
}

function figure(svg, caption) {
    const figure = document.createElement('figure');
    const text = document.createElement('figcaption');
    text.textContent = caption;
    figure.append(svg, text);
    return figure;
}

function formatAngle(degrees) {
    return degrees >= 2 ? `${degrees.toFixed(0)}°` : degrees >= 1 / 30 ? `${(degrees * 60).toFixed(0)}′` : `${(degrees * 3600).toFixed(0)}″`;
}

/** Where the image is among the constellations. */
function locatorChart(data, solution) {
    const diagonal = Math.hypot(solution.fieldWidth, solution.fieldHeight);
    const projection = new Projection(solution.ra, solution.dec, Math.min(90, Math.max(25, 1.25 * diagonal)));
    const {svg, content} = chart('locator-clip');
    drawGrid(content, projection);
    drawConstellations(content, projection, data, solution.constellation.abbreviation, true);
    const stars = data.stars;
    const dots = [];
    for (let i = 0; i < stars.length; i += 3) {
        const p = projection.project(stars[i], stars[i + 1]);
        if (p === null) continue;
        const r = Math.max(0.6, Math.min(4.5, 3.4 - 0.55 * stars[i + 2]));
        dots.push(`M${(p[0] - r).toFixed(1)} ${p[1].toFixed(1)}a${r.toFixed(2)} ${r.toFixed(2)} 0 1 0 ${(2 * r).toFixed(2)} 0a${r.toFixed(2)} ${r.toFixed(2)} 0 1 0 ${(-2 * r).toFixed(2)} 0`);
    }
    content.append(svgElement('path', {d: dots.join(''), class: 'sky-star'}));
    drawField(content, projection, solution.outline);
    drawCompass(svg);
    return figure(svg, `In ${solution.constellation.name}. Chart ${formatAngle(2 * projection.radius)} across, stars to magnitude 5.5.`);
}

/** The stars of the catalog around the image, and the ones which were matched. */
async function fieldChart(data, solution) {
    const diagonal = Math.hypot(solution.fieldWidth, solution.fieldHeight);
    const radius = Math.max(0.02, 0.85 * diagonal);
    if (radius > MAX_FIELD_CHART_RADIUS) return null;
    const query = new URLSearchParams({ra: solution.ra, dec: solution.dec, radius: radius * 1.05, catalog: solution.index, count: 1500});
    const response = await fetch('api/stars?' + query);
    const catalog = await response.json();
    if (!response.ok) throw new Error(catalog.error);
    const projection = new Projection(solution.ra, solution.dec, radius);
    const {svg, content} = chart('field-clip');
    drawGrid(content, projection);
    drawConstellations(content, projection, data, solution.constellation.abbreviation, false);
    const stars = catalog.stars;
    const dots = [];
    for (let i = 0; i < stars.length; i += 3) {
        const p = projection.project(stars[i], stars[i + 1]);
        if (p === null) continue;
        const r = 0.8 + 3.2 * (1 - stars[i + 2] / Math.max(1, catalog.levels - 1));
        dots.push(`M${(p[0] - r).toFixed(1)} ${p[1].toFixed(1)}a${r.toFixed(2)} ${r.toFixed(2)} 0 1 0 ${(2 * r).toFixed(2)} 0a${r.toFixed(2)} ${r.toFixed(2)} 0 1 0 ${(-2 * r).toFixed(2)} 0`);
    }
    content.append(svgElement('path', {d: dots.join(''), class: 'sky-star'}));
    content.append(svgElement('path', {d: projection.path(solution.outline.flat(), true, 0.5), class: 'sky-field outline'}));
    const matches = solution.matches.map(([ra, dec]) => projection.project(ra, dec)).filter(p => p !== null)
        .map(p => `M${(p[0] - 4.5).toFixed(1)} ${p[1].toFixed(1)}a4.5 4.5 0 1 0 9 0a4.5 4.5 0 1 0 -9 0`);
    content.append(svgElement('path', {d: matches.join(''), class: 'sky-match'}));
    drawObjects(content, projection, solution.objects || []);
    drawCompass(svg);
    return figure(svg, `${formatAngle(2 * radius)} across: ${stars.length / 3} stars of ${catalog.catalog}, `
        + `the ${solution.matches.length} matched ones circled.`);
}

/** The charts of a solution, which are filled when the data is loaded. */
function chartsView(solution) {
    const view = document.createElement('div');
    view.className = 'charts';
    view.textContent = 'Drawing the charts…';
    sky().then(async data => {
        const locator = locatorChart(data, solution);
        view.replaceChildren(locator);
        try {
            const field = await fieldChart(data, solution);
            if (field !== null) view.append(field);
        } catch (e) {
            view.append(figure(svgElement('svg', {}), `No star chart: ${e.message}`));
        }
    }).catch(e => {
        view.textContent = `No charts: ${e.message}`;
    });
    return view;
}
