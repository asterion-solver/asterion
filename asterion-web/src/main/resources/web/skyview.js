'use strict';

// The view of a solution: the image where it is on the sky, among the deep sky objects, the
// constellations and a grid of coordinates, which can be moved and zoomed. It's dragged with the
// mouse or a finger, and zoomed with the wheel, a pinch or its buttons. The projection is the
// stereographic one of the other charts, centered on the middle of the view, turned so that the
// image is upright as it was taken, or north up. The view can be saved as an image, as it is, and so can
// the whole image at its size, with its annotations.

/** The largest radius of the view, in degrees. */
const MAX_VIEW_RADIUS = 45;
/** The objects which the view asks for, the most prominent first: the ones which have room are drawn. */
const VIEW_OBJECTS = 300;
/** The objects of the catalogs of Lynds which the view draws, at most: they cut complexes into many pieces. */
const MAX_VIEW_LYNDS = 6;
/** The cells of the mesh which deforms the image, along its longest side. */
const MESH_CELLS = 12;
/** Outlines larger than this, in pixels, have their label inside: next to them, it would be next to other objects. */
const VIEW_LABELS_INSIDE = 60;
/** How much the buttons zoom. */
const ZOOM_STEP = 1.5;
/** The largest zoom on the image: the pixels of the screen for each pixel of the image. */
const MAX_ZOOM = 8;
/**
 * The image is decoded at its size once the view shows more of its pixels than its picture has, by this
 * factor: the picture is a thumbnail.
 */
const SHARPER = 1.1;
/** The part of the view which the image fills when the view is fitted to it. */
const FIT_MARGIN = 0.94;
/** The size of the corner of the compass, in pixels. */
const COMPASS = 90;
/** The size of the corner of the button which shows the view on the whole screen, in pixels. */
const FULL_SCREEN_BUTTON = 48;
/** The icons of that button, drawn with a stroke in a 24 pixels square: to enter the full screen, and to leave it. */
const FULL_SCREEN_ICONS = {enter: 'M4 9V4h5M15 4h5v5M20 15v5h-5M9 20H4v-5', exit: 'M9 4v5H4M20 9h-5V4M15 20v-5h5M4 15h5v5'};
/**
 * The width of the layout of the annotations of a saved image, in pixels: they are drawn as on a view of
 * this width, then scaled with the image, so that they keep their proportions whatever its size.
 */
const SAVED_LAYOUT_WIDTH = 1200;
/** Images wider than this, in degrees along their diagonal, show the constellations when saved. */
const SAVED_CONSTELLATIONS = 8;
/** The largest saved images, which browsers can encode: their longest side, and their pixels. */
const MAX_SAVED_SIDE = 8192;
const MAX_SAVED_PIXELS = 64e6;
/** The longest side of a saved image at its size: the one of the sensors of most cameras. */
const MAX_SAVED_IMAGE_SIDE = 16384;

/** The width of a label of the view, about: its font is 12 pixels high. */
function viewLabelWidth(text) {
    return text.length * 7;
}

/**
 * The view of a solution. The image is drawn once the page has it: a "picture" event gives it, whose
 * detail is the picture of the image, see picture() in app.js.
 */
function skyView(solution) {
    const diagonal = Math.hypot(solution.fieldWidth, solution.fieldHeight);
    // the radius of a view where the image is at its largest zoom
    const minRadius = () => {
        const {width, height} = projection;
        return 2 * Math.atan((Math.min(width, height) / 2 - 4) * solution.scale * RAD / 3600 / (2 * MAX_ZOOM)) / RAD;
    };
    const state = {ra: solution.ra, dec: solution.dec, radius: null, imageUp: true};

    const view = element('figure', undefined, 'sky-view');
    const stage = element('div', undefined, 'sky-stage');
    // the shape of the image, upright, within reason
    stage.style.aspectRatio = String(Math.min(2, Math.max(0.75, solution.fieldWidth / solution.fieldHeight)));
    const canvas = element('canvas');
    const svg = svgElement('svg', {class: 'sky-overlay'});
    stage.append(canvas, svg, fullScreenButton(view));
    const controls = element('div', undefined, 'sky-controls');
    const button = (text, title, action) => {
        const b = element('button', text);
        b.type = 'button';
        b.title = title;
        b.addEventListener('click', action);
        controls.append(b);
        return b;
    };
    const checkbox = (text, title, checked) => {
        const label = element('label', undefined, 'sky-toggle');
        label.title = title;
        const input = element('input');
        input.type = 'checkbox';
        input.checked = checked;
        input.addEventListener('change', () => schedule());
        label.append(input, ' ' + text);
        controls.append(label);
        return input;
    };
    button('−', 'Zoom out', () => zoom(ZOOM_STEP, center()));
    button('+', 'Zoom in', () => zoom(1 / ZOOM_STEP, center()));
    button('Fit', 'Back to the image, which fills the view', () => {
        state.ra = solution.ra;
        state.dec = solution.dec;
        state.radius = null;
        schedule();
    });
    const orientationButton = button('North up', 'Turn the view so that north is up, or the image upright', () => {
        state.imageUp = !state.imageUp;
        orientationButton.textContent = state.imageUp ? 'North up' : 'Image up';
        schedule();
    });
    const showImage = checkbox('Image', 'Show the image', true);
    showImage.disabled = true;
    const showAnnotations = checkbox('Annotations', 'Show the coordinates, the constellations and the deep sky objects', true);
    const saveViewButton = button('Save view (.jpg)', 'The view as it is, at the resolution of the image where it shows it',
        () => saving(saveViewButton, () => renderView(projection, thumbnail && showImage.checked ? thumbnail : null, decodeFull, svg, stage, solution),
            '.view.jpg'));
    const saveImageButton = button('Save annotated image (.jpg)', 'The whole image at its size, with its annotations',
        () => saving(saveImageButton, () => renderImage(solution, thumbnail, decodeFull, showAnnotations.checked, skyData, stage), '.annotated.jpg'));
    saveImageButton.disabled = true;
    const zoomLevel = element('span', undefined, 'muted sky-zoom');
    zoomLevel.title = 'The pixels of the screen for each pixel of the image';
    controls.append(zoomLevel);
    const failure = element('div', 'The browser couldn\'t make the image: it may be too large for it.', 'muted');
    failure.hidden = true;
    const caption = element('figcaption');
    view.append(stage, controls, failure, caption);

    // the picture of the image as {bitmap, bottomUp, mesh}: its bitmap, whether its rows are bottom up, which turns
    // the view of an upright image, and the positions on the sky of the vertices of its mesh
    let picture = null;
    let thumbnail = null;
    // the image at its size in the same form, decoded once when the view or a saved image needs it, then drawn by the
    // view: a promise of it or of null when the browser can't make it, and the image itself once decoded
    let fullImage = null;
    let full = null;
    let loading = false;
    const decodeFull = () => fullImage ??= picture.full()
        .then(decoded => decoded && {...decoded, mesh: imageMesh(solution, decoded.bitmap, decoded.bottomUp)})
        .catch(() => null)
        .then(decoded => {
            full = decoded;
            loading = false;
            schedule();
            return decoded;
        });
    view.addEventListener('picture', event => {
        picture = event.detail;
        thumbnail = {bitmap: picture.bitmap, bottomUp: picture.bottomUp, mesh: imageMesh(solution, picture.bitmap, picture.bottomUp)};
        showImage.disabled = false;
        saveImageButton.disabled = false;
        schedule();
    });

    // the constellations, once loaded
    let skyData = null;
    sky().then(data => {
        skyData = data;
        schedule();
    }).catch(() => {
        // the view is still useful without them
    });

    // the objects around the view, fetched again when the view leaves the disc they were fetched for,
    // or zooms in far enough for less prominent objects to have room
    let objects = [];
    let fetched = null;
    let fetchTimer = null;
    let requests = 0;
    const wantObjects = reach => {
        const needed = Math.min(90, reach);
        const position = vector(state.ra, state.dec);
        if (fetched && angle(position, fetched.center) + needed <= fetched.radius + 1e-9 && fetched.radius <= 2.5 * needed) return;
        clearTimeout(fetchTimer);
        fetchTimer = setTimeout(async () => {
            const radius = Math.min(90, needed * 1.2);
            const request = ++requests;
            const query = new URLSearchParams({ra: state.ra, dec: state.dec, radius, count: VIEW_OBJECTS});
            try {
                const response = await fetch('api/objects?' + query);
                const data = await response.json();
                if (request !== requests || !response.ok) return;
                objects = data.objects;
                fetched = {center: position, radius};
                schedule();
            } catch {
                // the objects already shown stay
            }
        }, 150);
    };

    // the projection of the view, of the given size, whose radius fits the image when it's not set yet
    const projectionOf = (width, height) => {
        const reachOf = radius => radius * Math.hypot(width, height) / Math.min(width, height);
        const orientation = state.imageUp ? uprightOrientation(solution, picture ? picture.bottomUp : false) : [1, 0, 0, 1];
        if (state.radius === null) {
            // the size of the outline of the image is about inversely proportional to the radius
            let radius = Math.min(MAX_VIEW_RADIUS, diagonal);
            for (let i = 0; i < 2; i++) {
                const p = new Projection(state.ra, state.dec, radius, width, height, reachOf(radius), orientation);
                const points = solution.outline.map(([ra, dec]) => p.project(ra, dec)).filter(q => q !== null);
                const xs = points.map(q => q[0]);
                const ys = points.map(q => q[1]);
                const ratio = Math.max((Math.max(...xs) - Math.min(...xs)) / (FIT_MARGIN * width), (Math.max(...ys) - Math.min(...ys)) / (FIT_MARGIN * height));
                if (!(ratio > 0)) break;
                radius = Math.min(MAX_VIEW_RADIUS, radius * ratio);
            }
            state.radius = radius;
        }
        return new Projection(state.ra, state.dec, state.radius, width, height, reachOf(state.radius), orientation);
    };

    // drawing, at most once a frame
    let frame = 0;
    const schedule = () => {
        if (!frame) frame = requestAnimationFrame(() => {
            frame = 0;
            draw();
        });
    };
    const resize = new ResizeObserver(() => schedule());
    resize.observe(stage);
    let projection = null;

    const draw = () => {
        if (!view.isConnected && projection) {
            // the result was replaced: the pictures go with it
            resize.disconnect();
            if (picture) picture.bitmap.close();
            if (fullImage) fullImage.then(decoded => decoded && decoded.bitmap.close());
            return;
        }
        const {width, height} = stage.getBoundingClientRect();
        if (width === 0 || height === 0) return;
        projection = projectionOf(width, height);
        const ratio = window.devicePixelRatio || 1;
        canvas.width = Math.round(width * ratio);
        canvas.height = Math.round(height * ratio);
        const context = canvas.getContext('2d');
        context.setTransform(ratio, 0, 0, ratio, 0, 0);
        context.clearRect(0, 0, width, height);
        if (thumbnail && showImage.checked) {
            drawImage(context, projection, solution, full || thumbnail);
            // the view shows more pixels than the picture has: the image at its size is sharper
            if (!fullImage && zoomOf(projection, solution) * ratio * imageSize(solution).width > SHARPER * thumbnail.bitmap.width) {
                loading = true;
                decodeFull();
            }
        }
        svg.setAttribute('viewBox', `0 0 ${width} ${height}`);
        let shown = 0;
        if (showAnnotations.checked) {
            const content = svgElement('g', {});
            // the corners of the compass and of the full screen button, which labels leave free
            const taken = [[width - COMPASS, 0, width, COMPASS], [width - FULL_SCREEN_BUTTON, height - FULL_SCREEN_BUTTON, width, height]];
            drawViewGrid(content, projection, taken);
            // the constellations, once the view is wider than the image
            if (skyData && state.radius > 1.5 * diagonal) {
                drawConstellations(content, projection, skyData, solution.constellation.abbreviation, state.radius > 4, true);
            }
            content.append(svgElement('path', {d: projection.path(solution.outline.flat(), true, 0.5), class: 'sky-field outline'}));
            shown = drawViewObjects(content, projection, objects, taken);
            drawViewCompass(content, projection);
            svg.replaceChildren(content);
            wantObjects(projection.reach);
        } else {
            svg.replaceChildren();
        }
        zoomLevel.textContent = `Zoom ${formatZoom(zoomOf(projection, solution))}` + (loading ? ', loading the image at its size' : '');
        caption.textContent = `${formatAngle(2 * state.radius)} across, centered on ${sexagesimal(state.ra, true)} ${sexagesimal(state.dec, false)}`
            + (showAnnotations.checked && fetched ? `, ${shown} deep sky object${shown === 1 ? '' : 's'} shown` : '')
            + '. Drag to move, wheel or pinch to zoom.';
    };

    // gestures: the position under the pointers stays under them
    const center = () => [projection.cx, projection.cy];
    const zoom = (factor, [x, y]) => {
        if (!projection) return;
        const radius = Math.min(MAX_VIEW_RADIUS, Math.max(minRadius(), state.radius * factor));
        const f = radius / state.radius;
        [state.ra, state.dec] = projection.unproject(projection.cx + (x - projection.cx) * (1 - f), projection.cy + (y - projection.cy) * (1 - f));
        state.radius = radius;
        schedule();
    };
    const pointers = new Map();
    let gesture = null;
    const point = event => {
        const box = stage.getBoundingClientRect();
        return [event.clientX - box.left, event.clientY - box.top];
    };
    const middle = () => {
        const points = [...pointers.values()];
        return [points.reduce((s, p) => s + p[0], 0) / points.length, points.reduce((s, p) => s + p[1], 0) / points.length];
    };
    const spread = () => {
        const points = [...pointers.values()];
        return points.length < 2 ? 0 : Math.hypot(points[0][0] - points[1][0], points[0][1] - points[1][1]);
    };
    // a gesture starts again when a finger is added or lifted, so that the view doesn't jump
    const begin = () => {
        gesture = pointers.size && projection ? {projection, radius: state.radius, middle: middle(), spread: spread()} : null;
    };
    stage.addEventListener('pointerdown', event => {
        if (event.button !== 0) return;
        stage.setPointerCapture(event.pointerId);
        pointers.set(event.pointerId, point(event));
        stage.classList.add('dragging');
        begin();
    });
    stage.addEventListener('pointermove', event => {
        if (!pointers.has(event.pointerId) || !gesture) return;
        pointers.set(event.pointerId, point(event));
        const [x, y] = middle();
        const g = gesture.projection;
        [state.ra, state.dec] = g.unproject(g.cx - (x - gesture.middle[0]), g.cy - (y - gesture.middle[1]));
        if (pointers.size >= 2 && gesture.spread > 0) {
            state.radius = Math.min(MAX_VIEW_RADIUS, Math.max(minRadius(), gesture.radius * gesture.spread / spread()));
        }
        schedule();
    });
    const end = event => {
        if (!pointers.delete(event.pointerId)) return;
        if (pointers.size === 0) stage.classList.remove('dragging');
        begin();
    };
    stage.addEventListener('pointerup', end);
    stage.addEventListener('pointercancel', end);
    stage.addEventListener('wheel', event => {
        event.preventDefault();
        // lines or pages for some mice, pixels for trackpads
        const delta = event.deltaY * (event.deltaMode === 1 ? 40 : event.deltaMode === 2 ? 800 : 1);
        zoom(Math.exp(Math.max(-1, Math.min(1, delta * 0.002))), point(event));
    }, {passive: false});

    // saves the image which a function renders, its button disabled meanwhile
    const saving = async (saveButton, render, suffix) => {
        if (!projection) return;
        saveButton.disabled = true;
        failure.hidden = true;
        try {
            const blob = await render();
            if (blob) {
                save(blob, baseName(solution.image) + suffix);
            } else {
                failure.hidden = false;
            }
        } catch {
            failure.hidden = false;
        } finally {
            saveButton.disabled = false;
        }
    };

    schedule();
    return view;
}

/**
 * The button which shows a view on the whole screen, with its controls, and back: none in the browsers which
 * can't, such as the ones of phones which only show videos on the whole screen.
 */
function fullScreenButton(view) {
    const button = element('button', undefined, 'sky-full-screen');
    button.type = 'button';
    button.hidden = !document.fullscreenEnabled;
    const update = () => {
        const full = document.fullscreenElement === view;
        button.title = full ? 'Leave the full screen' : 'Full screen';
        button.setAttribute('aria-label', button.title);
        const icon = svgElement('svg', {viewBox: '0 0 24 24', 'aria-hidden': 'true'});
        icon.append(svgElement('path', {d: full ? FULL_SCREEN_ICONS.exit : FULL_SCREEN_ICONS.enter}));
        button.replaceChildren(icon);
    };
    // a click on the button doesn't drag the view
    button.addEventListener('pointerdown', event => event.stopPropagation());
    button.addEventListener('click', () => {
        if (document.fullscreenElement === view) {
            document.exitFullscreen();
        } else {
            view.requestFullscreen().catch(() => {
                // refused, by the browser or its settings: the view stays in the page
            });
        }
    });
    // the event is sent to the element which enters or leaves the full screen
    view.addEventListener('fullscreenchange', update);
    update();
    return button;
}

/** The size of a solved image, in pixels. */
function imageSize(solution) {
    return {width: Math.round(solution.fieldWidth * 3600 / solution.scale), height: Math.round(solution.fieldHeight * 3600 / solution.scale)};
}

/**
 * The zoom of a view on its image: the pixels of the view for each pixel of the image, at the center of the view,
 * where the projection has its scale.
 */
function zoomOf(projection, solution) {
    return projection.scale * solution.scale * RAD / 3600;
}

/** A zoom as a percentage, with two significant digits when it's small. */
function formatZoom(zoom) {
    const percent = 100 * zoom;
    return `${percent >= 10 ? Math.round(percent) : Number(percent.toPrecision(2))} %`;
}

/**
 * The projection of a solved image on itself: a position of the sky is where the image shows it, through the
 * WCS of its solution, scaled to a width; the same methods as the projection of a view, so that an image is
 * annotated as a view is. Its center and radius, which the grid and the compass use, are the ones of the image.
 */
class ImageProjection extends Projection {
    constructor(solution, width, bottomUp) {
        const size = imageSize(solution);
        const height = width * size.height / size.width;
        super(solution.ra, solution.dec, Math.hypot(solution.fieldWidth, solution.fieldHeight) / 2, width, height);
        this.wcs = wcsProjection(solution.wcs);
        this.size = size;
        this.k = width / size.width;
        this.bottomUp = bottomUp;
    }

    project(ra, dec) {
        const pixel = this.wcs.toPixel(ra, dec);
        if (!pixel) return null;
        // pixels are counted from 0 at their centers, as in the mesh of the image
        const [x, y] = pixel;
        return [(x + 0.5) * this.k, (this.bottomUp ? this.size.height - y - 0.5 : y + 0.5) * this.k];
    }

    unproject(x, y) {
        return this.wcs.toSky(x / this.k - 0.5, this.bottomUp ? this.size.height - y / this.k - 0.5 : y / this.k - 0.5);
    }
}

/**
 * The orientation which shows an image upright, as it was taken: from the directions of its axes on a chart
 * north up, at its center. Its rows are bottom up when the first row of the image is shown at the bottom.
 */
function uprightOrientation(solution, bottomUp) {
    const wcs = wcsProjection(solution.wcs);
    const {width, height} = imageSize(solution);
    const [x, y] = [(width - 1) / 2, (height - 1) / 2];
    const step = Math.max(1, Math.min(width, height) / 20);
    const north = new Projection(solution.ra, solution.dec, 1);
    const at = (px, py) => {
        const [ra, dec] = wcs.toSky(px, py);
        return north.project(ra, dec);
    };
    const c = at(x, y);
    const r = at(x + step, y);
    const d = at(x, bottomUp ? y - step : y + step);
    if (!c || !r || !d) return [1, 0, 0, 1];
    // the first row of the matrix turns the right of the image to the right of the view, the second one,
    // perpendicular, keeps the bottom of the image at the bottom of the view, mirroring the chart if needed
    const length = Math.hypot(r[0] - c[0], r[1] - c[1]);
    const [rx, ry] = [(r[0] - c[0]) / length, (r[1] - c[1]) / length];
    const sign = Math.sign(-ry * (d[0] - c[0]) + rx * (d[1] - c[1])) || 1;
    return [rx, ry, -sign * ry, sign * rx];
}

/**
 * The mesh which deforms an image: the pixels of its picture at the vertices of a grid, and their positions on
 * the sky, computed once for the whole image, as the distortion of the solution makes them costly, or for a region
 * of the picture, [left, top, right, bottom].
 */
function imageMesh(solution, image, bottomUp, region = [0, 0, image.width, image.height]) {
    const wcs = wcsProjection(solution.wcs);
    const {width, height} = imageSize(solution);
    const k = image.width / width;
    const [u0, v0, u1, v1] = region;
    const [w, h] = [u1 - u0, v1 - v0];
    const nx = w >= h ? MESH_CELLS : Math.max(1, Math.round(MESH_CELLS * w / h));
    const ny = h >= w ? MESH_CELLS : Math.max(1, Math.round(MESH_CELLS * h / w));
    const vertices = [];
    for (let j = 0; j <= ny; j++) {
        for (let i = 0; i <= nx; i++) {
            const u = u0 + w * i / nx;
            const v = v0 + h * j / ny;
            // from the pixels of the picture to the ones of the image
            const x = u / k - 0.5;
            const y = bottomUp ? height - v / k - 0.5 : v / k - 0.5;
            vertices.push({source: [u, v], sky: wcs.toSky(x, y)});
        }
    }
    return {nx, ny, vertices};
}

/**
 * Draws an image on a view, {bitmap, bottomUp, mesh}: through the mesh of the whole image when the view shows most
 * of it, or through a mesh of the part which it shows when it's zoomed in, whose cells stay small on the view, so
 * that the triangles which approximate the projection meet without seams.
 */
function drawImage(context, projection, solution, image) {
    const region = visibleRegion(projection, solution, image);
    const whole = image.bitmap.width * image.bitmap.height;
    const mesh = region && (region[2] - region[0]) * (region[3] - region[1]) < whole / 4
        ? imageMesh(solution, image.bitmap, image.bottomUp, region)
        : image.mesh;
    drawImageMesh(context, projection, image.bitmap, mesh);
}

/**
 * The part of the picture of an image which a view shows, [left, top, right, bottom], with a margin: the box of the
 * edges of the view on the picture, within it; null when the view is so wide that its edges aren't all on the
 * image's side of the sky, or when it doesn't show the image.
 */
function visibleRegion(projection, solution, image) {
    const wcs = wcsProjection(solution.wcs);
    const {width, height} = imageSize(solution);
    const k = image.bitmap.width / width;
    const [w, h] = [projection.width, projection.height];
    const box = [Infinity, Infinity, -Infinity, -Infinity];
    const samples = 8;
    for (let i = 0; i <= samples; i++) {
        for (const [x, y] of [[w * i / samples, 0], [w * i / samples, h], [0, h * i / samples], [w, h * i / samples]]) {
            const pixel = wcs.toPixel(...projection.unproject(x, y));
            if (!pixel) return null;
            const u = (pixel[0] + 0.5) * k;
            const v = (image.bottomUp ? height - pixel[1] - 0.5 : pixel[1] + 0.5) * k;
            box[0] = Math.min(box[0], u);
            box[1] = Math.min(box[1], v);
            box[2] = Math.max(box[2], u);
            box[3] = Math.max(box[3], v);
        }
    }
    // the edges of the view may bend on the picture: a margin of a tenth of the region
    const margin = 0.1 * Math.max(box[2] - box[0], box[3] - box[1]);
    const region = [Math.max(0, box[0] - margin), Math.max(0, box[1] - margin),
        Math.min(image.bitmap.width, box[2] + margin), Math.min(image.bitmap.height, box[3] + margin)];
    return region[2] > region[0] && region[3] > region[1] ? region : null;
}

/**
 * Draws an image on the view: each cell of its mesh is two triangles, each stretched to where its vertices are,
 * even far outside the view, where the cells of a view zoomed in on the image have them.
 */
function drawImageMesh(context, projection, image, mesh) {
    const points = mesh.vertices.map(v => projection.project(v.sky[0], v.sky[1], true));
    const at = (i, j) => (mesh.nx + 1) * j + i;
    for (let j = 0; j < mesh.ny; j++) {
        for (let i = 0; i < mesh.nx; i++) {
            const cell = [at(i, j), at(i + 1, j), at(i + 1, j + 1), at(i, j + 1)];
            for (const triangle of [[cell[0], cell[1], cell[2]], [cell[0], cell[2], cell[3]]]) {
                const to = triangle.map(n => points[n]);
                if (to.some(p => p === null)) continue;
                const xs = to.map(p => p[0]);
                const ys = to.map(p => p[1]);
                if (Math.max(...xs) < 0 || Math.min(...xs) > projection.width || Math.max(...ys) < 0 || Math.min(...ys) > projection.height) continue;
                drawTriangle(context, image, triangle.map(n => mesh.vertices[n].source), to);
            }
        }
    }
}

/** Draws a triangle of an image where another one is: the affine transform which maps one to the other, clipped to it. */
function drawTriangle(context, image, from, to) {
    const [[x0, y0], [x1, y1], [x2, y2]] = from;
    const [[u0, v0], [u1, v1], [u2, v2]] = to;
    const det = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0);
    if (det === 0) return;
    const a = ((u1 - u0) * (y2 - y0) - (u2 - u0) * (y1 - y0)) / det;
    const c = ((u2 - u0) * (x1 - x0) - (u1 - u0) * (x2 - x0)) / det;
    const b = ((v1 - v0) * (y2 - y0) - (v2 - v0) * (y1 - y0)) / det;
    const d = ((v2 - v0) * (x1 - x0) - (v1 - v0) * (x2 - x0)) / det;
    const e = u0 - a * x0 - c * y0;
    const f = v0 - b * x0 - d * y0;
    // the clipped triangles are enlarged by a fraction of a pixel, which hides the seams between them
    const cx = (u0 + u1 + u2) / 3;
    const cy = (v0 + v1 + v2) / 3;
    const grow = ([u, v]) => {
        const length = Math.hypot(u - cx, v - cy) || 1;
        return [u + 0.6 * (u - cx) / length, v + 0.6 * (v - cy) / length];
    };
    context.save();
    context.beginPath();
    to.map(grow).forEach(([u, v], n) => n === 0 ? context.moveTo(u, v) : context.lineTo(u, v));
    context.closePath();
    context.clip();
    context.transform(a, b, c, d, e, f);
    // only the part of the image under the triangle, with a pixel around it: images at their size are large
    const sx = Math.max(0, Math.floor(Math.min(x0, x1, x2)) - 1);
    const sy = Math.max(0, Math.floor(Math.min(y0, y1, y2)) - 1);
    const sw = Math.min(image.width, Math.ceil(Math.max(x0, x1, x2)) + 1) - sx;
    const sh = Math.min(image.height, Math.ceil(Math.max(y0, y1, y2)) + 1) - sy;
    if (sw > 0 && sh > 0) context.drawImage(image, sx, sy, sw, sh, sx, sy, sw, sh);
    context.restore();
}

/**
 * Draws the grid of coordinates of the view, with the labels of its lines where they leave the view:
 * declinations on the sides, right ascensions at the bottom, or else on other edges. The boxes of the
 * labels are added to the taken ones, which they avoid.
 */
function drawViewGrid(content, projection, taken) {
    const {width, height} = projection;
    const inside = p => p && p[0] >= 0 && p[1] >= 0 && p[0] <= width && p[1] <= height;
    // the extent of the view on the sky, the right ascensions relative to the one of its center
    const [ra0, dec0] = projection.unproject(projection.cx, projection.cy);
    let decMin = 90, decMax = -90, raMin = 180, raMax = -180;
    for (let i = 0; i <= 8; i++) {
        for (let j = 0; j <= 8; j++) {
            const [ra, dec] = projection.unproject(i * width / 8, j * height / 8);
            const dra = ((ra - ra0 + 540) % 360) - 180;
            decMin = Math.min(decMin, dec);
            decMax = Math.max(decMax, dec);
            raMin = Math.min(raMin, dra);
            raMax = Math.max(raMax, dra);
        }
    }
    for (const pole of [90, -90]) {
        if (inside(projection.project(0, pole))) {
            if (pole > 0) {
                decMax = 90;
            } else {
                decMin = -90;
            }
            raMin = -180;
            raMax = 180;
        }
    }
    const steps = GRID_STEPS.concat([45, 60, 90]);
    const nice = target => steps.find(s => s >= target) || 90;
    const decStep = nice(2 * projection.radius / 5);
    const raStep = nice(decStep / Math.max(0.05, Math.cos(dec0 * RAD)));
    const samples = 120;
    const lines = [];
    for (let dec = Math.ceil(decMin / decStep) * decStep; dec <= decMax; dec += decStep) {
        if (Math.abs(dec) > 89.999) continue;
        const points = [];
        for (let i = 0; i <= samples; i++) points.push(projection.project(ra0 + raMin + (raMax - raMin) * i / samples, dec));
        lines.push([points, ['left', 'right', 'bottom', 'top'], dmsLabel(dec, decStep)]);
    }
    for (let dra = Math.ceil((ra0 + raMin) / raStep) * raStep - ra0; dra <= raMax; dra += raStep) {
        const points = [];
        for (let i = 0; i <= samples; i++) points.push(projection.project(ra0 + dra, decMin + (decMax - decMin) * i / samples));
        lines.push([points, ['bottom', 'top', 'left', 'right'], hmsLabel(((ra0 + dra) % 360 + 360) % 360, raStep)]);
    }
    const path = points => {
        let d = '', pen = false;
        for (const p of points) {
            if (!p) {
                pen = false;
                continue;
            }
            d += `${pen ? 'L' : 'M'}${p[0].toFixed(1)} ${p[1].toFixed(1)}`;
            pen = true;
        }
        return d;
    };
    content.append(svgElement('path', {d: lines.map(([points]) => path(points)).join(''), class: 'sky-grid'}));
    // the label of a line where it crosses an edge, on the first edge of the given ones which has room
    for (const [points, edges, text] of lines) {
        const crossings = [];
        for (let i = 1; i < points.length; i++) {
            const [p, q] = [points[i - 1], points[i]];
            if (!p || !q || inside(p) === inside(q)) continue;
            const [x, y] = inside(p) ? p : q;
            const distances = {left: x, right: width - x, top: y, bottom: height - y};
            crossings.push([x, y, Object.keys(distances).reduce((a, b) => distances[a] <= distances[b] ? a : b)]);
        }
        const w = text.length * 7;
        for (const edge of edges) {
            const crossing = crossings.find(c => c[2] === edge);
            if (!crossing) continue;
            const [x, y] = crossing;
            const along = Math.min(Math.max(x + 4, 4), width - 4 - w);
            const at = {
                left: [4, y - 4],
                right: [width - 4 - w, y - 4],
                bottom: [along, height - 6],
                top: [along, 16],
            }[edge];
            const box = [at[0], at[1] - 11, at[0] + w, at[1] + 2];
            if (box[1] < 0 || box[3] > height || taken.some(t => intersects(box, t))) continue;
            content.append(svgElement('text', {x: at[0], y: at[1], class: 'sky-grid-label'}, text));
            taken.push(box);
            break;
        }
    }
}

/** The compass, in the corner of the view: the directions of north and east at its center. */
function drawViewCompass(content, projection) {
    const [ra, dec] = projection.unproject(projection.cx, projection.cy);
    const step = projection.radius / 20;
    const origin = projection.project(ra, dec);
    const north = projection.project(ra, Math.min(90, dec + step));
    const east = projection.project(ra + step / Math.max(0.01, Math.cos(dec * RAD)), dec);
    if (!origin || !north || !east) return;
    const corner = [projection.width - COMPASS / 2, COMPASS / 2];
    for (const [to, name] of [[north, 'N'], [east, 'E']]) {
        const dx = to[0] - origin[0];
        const dy = to[1] - origin[1];
        const length = Math.hypot(dx, dy) || 1;
        content.append(svgElement('line', {x1: corner[0], y1: corner[1], x2: corner[0] + 24 * dx / length, y2: corner[1] + 24 * dy / length, class: 'sky-compass-arrow'}));
        content.append(svgElement('text', {x: corner[0] + 34 * dx / length, y: corner[1] + 34 * dy / length + 5, class: 'sky-compass'}, name));
    }
}

/**
 * Draws the deep sky objects, the most prominent first, as they come: their outlines, from their sizes
 * and position angles, with their names. An object is drawn only where its name has room, covering
 * neither other names nor small objects: an outline without a name is noise.
 *
 * @param taken the boxes which the names avoid, to which theirs are added
 * @return the number of objects drawn
 */
function drawViewObjects(content, projection, objects, taken) {
    const {width, height} = projection;
    const free = box => box[0] >= 0 && box[2] <= width && box[1] >= 0 && box[3] <= height && !taken.some(t => intersects(box, t));
    let lynds = 0;
    let shown = 0;
    for (const o of objects) {
        const fromLynds = /^L[BD]N /.test(o.name);
        if (fromLynds && lynds >= MAX_VIEW_LYNDS) continue;
        const center = projection.project(o.ra, o.dec);
        if (center === null) continue;
        // an object whose orientation isn't known is a circle of the same area: an ellipse would have to
        // point somewhere, and a circle of its major axis would be much larger than the object
        const oriented = o.angle !== undefined && o.angle !== null;
        const major = (o.majorArcmin || 0) / 120;
        const minor = (o.minorArcmin || o.majorArcmin || 0) / 120;
        const a = oriented ? major : Math.sqrt(major * minor);
        const b = oriented ? minor : a;
        const angle = oriented ? o.angle * RAD : 0;
        const outline = [];
        for (let i = 0; i < 36 && a > 0; i++) {
            const t = 2 * Math.PI * i / 36;
            const north = a * Math.cos(t) * Math.cos(angle) - b * Math.sin(t) * Math.sin(angle);
            const east = a * Math.cos(t) * Math.sin(angle) + b * Math.sin(t) * Math.cos(angle);
            const p = projection.project(o.ra + east / Math.max(0.01, Math.cos(o.dec * RAD)), o.dec + north);
            if (p !== null) outline.push(p);
        }
        const extent = outline.reduce((m, p) => Math.max(m, Math.hypot(p[0] - center[0], p[1] - center[1])), 0);
        const radius = Math.max(extent, 3);
        if (center[0] + radius < 0 || center[0] - radius > width || center[1] + radius < 0 || center[1] - radius > height) continue;
        const marker = [center[0] - radius, center[1] - radius, center[0] + radius, center[1] + radius];
        if (radius < VIEW_LABELS_INSIDE && taken.some(t => intersects(marker, t))) continue;
        const w = viewLabelWidth(o.name);
        // boxes of labels centered on x, with their baseline at y
        const at = (x, y) => [x - w / 2, y - 11, x + w / 2, y + 3];
        const candidates = radius >= VIEW_LABELS_INSIDE
            ? [at(center[0], center[1] + 4), at(center[0], center[1] + 20), at(center[0], center[1] - 12)]
            : [at(center[0] + radius + 3 + w / 2, center[1] + 4), at(center[0] - radius - 3 - w / 2, center[1] + 4),
                at(center[0], center[1] + radius + 14), at(center[0], center[1] - radius - 5)];
        const box = candidates.find(free);
        if (!box) continue;
        const shape = extent >= 3
            ? svgElement('path', {d: outline.map((p, i) => `${i ? 'L' : 'M'}${p[0].toFixed(1)} ${p[1].toFixed(1)}`).join('') + 'Z', class: 'sky-object'})
            : svgElement('circle', {cx: center[0], cy: center[1], r: 3, class: 'sky-object'});
        const label = svgElement('text', {x: (box[0] + box[2]) / 2, y: box[3] - 3, class: 'sky-object-label'}, o.name);
        const title = `${o.label}: ${o.type}` + (o.majorArcmin ? `, ${formatAngle(o.majorArcmin / 60)} across` : '')
            + (o.magnitude !== undefined ? `, magnitude ${o.magnitude}` : '');
        shape.append(svgElement('title', {}, title));
        label.append(svgElement('title', {}, title));
        content.append(shape, label);
        taken.push(box);
        if (radius < VIEW_LABELS_INSIDE) taken.push(marker);
        if (fromLynds) lynds++;
        shown++;
    }
    return shown;
}

/**
 * The view as a JPEG image, as it is: larger than on the screen, so that the image keeps its resolution
 * where the view shows it, within what browsers can encode. The annotations are scaled with it.
 *
 * @param thumbnail the picture of the image, {bitmap, bottomUp, mesh}, or null when the image isn't shown
 * @param decodeFull gives a promise of the image at its size in the same form, or of null, which the view keeps
 * @return a promise of the file, or of null when the browser can't encode it
 */
async function renderView(projection, thumbnail, decodeFull, svg, stage, solution) {
    const {width, height} = projection;
    const cap = Math.min(MAX_SAVED_SIDE / Math.max(width, height), Math.sqrt(MAX_SAVED_PIXELS / (width * height)));
    let factor = Math.min(cap, 2);
    let image = thumbnail;
    if (thumbnail) {
        const zoom = zoomOf(projection, solution);
        factor = Math.min(cap, Math.max(factor, 1 / zoom));
        // the picture is a thumbnail: the image at its size is used when the saved view shows more of it
        if (factor * zoom * imageSize(solution).width > SHARPER * thumbnail.bitmap.width) {
            image = await decodeFull() || thumbnail;
        }
    }
    const canvas = savedCanvas(Math.round(width * factor), Math.round(height * factor), stage);
    const context = canvas.getContext('2d');
    context.setTransform(factor, 0, 0, factor, 0, 0);
    if (image) {
        drawImage(context, projection, solution, image);
    }
    context.setTransform(1, 0, 0, 1, 0, 0);
    if (svg.childNodes.length) {
        await drawOverlay(context, svg, stage);
    }
    return await encode(canvas);
}

/**
 * The whole image at its size, as a JPEG image, with its annotations when asked: the grid of coordinates, the
 * deep sky objects of the solution, the compass, and the constellations of wide images. They are laid out as on a
 * view of {@link SAVED_LAYOUT_WIDTH} pixels, and scaled with the image. Images larger than what browsers can
 * encode are scaled down.
 *
 * @param thumbnail the picture of the image, {bitmap, bottomUp, mesh}
 * @param decodeFull gives a promise of the image at its size in the same form, or of null, which the view keeps
 * @param skyData the constellations, or null when they aren't loaded
 * @return a promise of the file, or of null when the browser can't make it
 */
async function renderImage(solution, thumbnail, decodeFull, annotations, skyData, stage) {
    const size = imageSize(solution);
    const factor = Math.min(1, MAX_SAVED_IMAGE_SIDE / Math.max(size.width, size.height), Math.sqrt(MAX_SAVED_PIXELS / (size.width * size.height)));
    const {bitmap, bottomUp} = await decodeFull() || thumbnail;
    const canvas = savedCanvas(Math.round(size.width * factor), Math.round(size.height * factor), stage);
    const context = canvas.getContext('2d');
    // the picture shows the image as the view does, its rows bottom up or not
    context.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
    if (annotations) {
        const projection = new ImageProjection(solution, SAVED_LAYOUT_WIDTH, bottomUp);
        const svg = svgElement('svg', {viewBox: `0 0 ${projection.width} ${projection.height}`});
        const taken = [[projection.width - COMPASS, 0, projection.width, COMPASS]];
        drawViewGrid(svg, projection, taken);
        if (skyData && Math.hypot(solution.fieldWidth, solution.fieldHeight) > SAVED_CONSTELLATIONS) {
            drawConstellations(svg, projection, skyData, solution.constellation.abbreviation, true, true);
        }
        drawViewObjects(svg, projection, solution.objects || [], taken);
        drawViewCompass(svg, projection);
        await drawOverlay(context, svg, stage);
    }
    return await encode(canvas);
}

/** The canvas of a saved image, filled with the background of the view. */
function savedCanvas(width, height, stage) {
    const canvas = element('canvas');
    canvas.width = width;
    canvas.height = height;
    const context = canvas.getContext('2d');
    context.fillStyle = getComputedStyle(stage).backgroundColor;
    context.fillRect(0, 0, width, height);
    return canvas;
}

/**
 * Draws annotations on the whole canvas of a saved image: an image of their own, with the rules of the page which
 * style them, scaled from their view box.
 */
async function drawOverlay(context, svg, stage) {
    const {width, height} = context.canvas;
    const copy = svg.cloneNode(true);
    copy.setAttribute('width', width);
    copy.setAttribute('height', height);
    const rules = [...document.styleSheets].flatMap(sheet => {
        try {
            return [...sheet.cssRules];
        } catch {
            return [];
        }
    }).map(rule => rule.cssText).filter(text => text.includes('.sky-'));
    const font = getComputedStyle(stage).fontFamily;
    copy.prepend(svgElement('style', {}, `svg { font-family: ${font}; }\n${rules.join('\n')}`));
    const url = URL.createObjectURL(new Blob([new XMLSerializer().serializeToString(copy)], {type: 'image/svg+xml'}));
    try {
        const overlay = new Image();
        overlay.src = url;
        await overlay.decode();
        context.drawImage(overlay, 0, 0, width, height);
    } finally {
        URL.revokeObjectURL(url);
    }
}

/** A canvas as a JPEG file: a promise of null when it's larger than what the browser can encode. */
function encode(canvas) {
    return new Promise(resolve => canvas.toBlob(resolve, 'image/jpeg', 0.92));
}
