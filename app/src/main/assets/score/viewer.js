/*
 * PianoSheet score viewer.
 *
 * Renders the MusicXML the app generates with OpenSheetMusicDisplay, offline:
 * the library is bundled next to this file and nothing is fetched from a
 * network.  The page draws the score and a play-head the app moves while the
 * take plays; it never reads or writes anything else.
 */
(function () {
    'use strict';

    var PAPER_ID = 'paper';
    var SCORE_ID = 'score';
    var SCROLLER_ID = 'scroller';

    var state = {
        osmd: null,
        dark: true,
        zoom: 1,
        ready: false,
        lastRequest: null
    };

    function byId(id) {
        return document.getElementById(id);
    }

    function notify(payload) {
        var text;
        try {
            text = JSON.stringify(payload);
        } catch (error) {
            text = '{"ok":false,"error":"serialize failed"}';
        }
        try {
            if (window.PianoSheetBridge && typeof window.PianoSheetBridge.onEvent === 'function') {
                window.PianoSheetBridge.onEvent(text);
            }
        } catch (ignored) {
            // The bridge is optional: the page still renders without the app.
        }
        return text;
    }

    function applyThemeClasses() {
        document.body.classList.toggle('theme-dark', state.dark === true);
        document.body.classList.toggle('theme-light', state.dark !== true);
        document.body.setAttribute('data-theme', state.dark ? 'dark' : 'light');
    }

    function colors() {
        return state.dark
            ? { music: '#EFEAF5', label: '#C7C0D4', title: '#F3EFF8' }
            : { music: '#1C1B1F', label: '#514C5A', title: '#1C1B1F' };
    }

    function clearNode(node) {
        while (node && node.firstChild) node.removeChild(node.firstChild);
    }

    function buildOsmd() {
        var score = byId(SCORE_ID);
        clearNode(score);
        var host = document.createElement('div');
        host.id = 'osmd';
        score.appendChild(host);
        var theme = colors();
        var options = {
            backend: 'svg',
            autoResize: true,
            drawTitle: false,
            drawSubtitle: false,
            drawComposer: false,
            drawLyricist: false,
            drawCredits: false,
            drawPartNames: false,
            drawPartNamesAndAbbreviations: false,
            drawMeasureNumbers: true,
            drawTimeSignatures: true,
            drawingParameters: 'compacttight',
            // Beam eighths and sixteenths the way a reader expects; the export
            // does not write <beam> elements, so the renderer groups them.
            autoBeam: true,
            autoBeamOptions: { beam_multi_note_groups: true, beam_rests: false },
            disableCursor: true,
            followCursor: false,
            defaultColorMusic: theme.music,
            defaultColorLabel: theme.label,
            defaultColorTitle: theme.title,
            defaultColorNotehead: theme.music,
            defaultColorStem: theme.music,
            defaultColorRest: theme.music
        };
        return new opensheetmusicdisplay.OpenSheetMusicDisplay(host, options);
    }

    function statusText(message) {
        var node = byId('status');
        if (!node) return;
        if (!message) {
            node.textContent = '';
            node.classList.add('hidden');
            return;
        }
        node.textContent = message;
        node.classList.remove('hidden');
    }

    // ------------------------------------------------------------- geometry

    function musicPages() {
        try {
            return state.osmd.GraphicSheet.MusicPages || [];
        } catch (error) {
            return [];
        }
    }

    function measureList() {
        try {
            return state.osmd.GraphicSheet.MeasureList || [];
        } catch (error) {
            return [];
        }
    }

    /** The graphical measure for a 0-based measure index, first staff. */
    function graphicalMeasure(index) {
        var list = measureList();
        var row = list[index];
        if (row && row.length && row[0]) return row[0];
        for (var system = 0; system < list.length; system += 1) {
            var entries = list[system];
            if (!entries) continue;
            for (var staff = 0; staff < entries.length; staff += 1) {
                var candidate = entries[staff];
                if (!candidate) continue;
                var number = null;
                try {
                    number = candidate.MeasureNumber;
                } catch (ignored) {
                }
                if (number === index + 1) return candidate;
            }
        }
        return null;
    }

    /** Pixels per engraving unit: taken from the rendered SVG itself. */
    function scaleInfo() {
        var svg = document.querySelector('#' + SCORE_ID + ' svg');
        var fallback = { pxPerUnit: 10 * (state.zoom || 1), offsetX: 0, offsetY: 0 };
        if (!svg) return fallback;
        var svgRect = svg.getBoundingClientRect();
        var paperRect = byId(PAPER_ID).getBoundingClientRect();
        fallback.offsetX = svgRect.left - paperRect.left;
        fallback.offsetY = svgRect.top - paperRect.top;
        var pages = musicPages();
        try {
            if (pages.length && pages[0].PositionAndShape) {
                var widthUnits = pages[0].PositionAndShape.Size.width;
                if (widthUnits > 0 && svgRect.width > 0) {
                    fallback.pxPerUnit = svgRect.width / widthUnits;
                }
            }
        } catch (ignored) {
        }
        return fallback;
    }

    function measureBox(measure) {
        var box;
        try {
            box = measure.PositionAndShape;
        } catch (ignored) {
            return null;
        }
        if (!box) return null;
        var scale = scaleInfo();
        var x = box.AbsolutePosition.x;
        var y = box.AbsolutePosition.y;
        var width = box.Size ? box.Size.width : 0;
        var height = box.Size ? box.Size.height : 0;
        return {
            left: scale.offsetX + x * scale.pxPerUnit,
            top: scale.offsetY + y * scale.pxPerUnit,
            width: width * scale.pxPerUnit,
            height: height * scale.pxPerUnit,
            scale: scale
        };
    }

    /** X position of the note nearest to (and not after) a beat in the measure. */
    function beatLeft(measure, beatInMeasure, measureBeats) {
        var box = measureBox(measure);
        if (!box) return null;
        var target = measureBeats > 0 ? beatInMeasure / measureBeats : 0;
        if (target < 0) target = 0;
        if (target > 1) target = 1;
        var chosen = null;
        var chosenFraction = -1;
        var entries = [];
        try {
            entries = measure.staffEntries || [];
        } catch (ignored) {
        }
        for (var i = 0; i < entries.length; i += 1) {
            var stamp = entries[i].relInMeasureTimestamp;
            if (!stamp) continue;
            var fraction = null;
            try {
                fraction = stamp.RealValue / (measureBeats / 4);
            } catch (ignored) {
            }
            if (fraction === null || !isFinite(fraction)) continue;
            if (fraction <= target + 0.001 && fraction >= chosenFraction) {
                chosenFraction = fraction;
                chosen = entries[i];
            }
        }
        var left = box.left + box.width * target;
        if (chosen) {
            try {
                var entryX = chosen.PositionAndShape.AbsolutePosition.x;
                left = box.scale.offsetX + entryX * box.scale.pxPerUnit;
            } catch (ignored) {
            }
        }
        return { left: left, top: box.top, height: box.height, box: box };
    }

    // ------------------------------------------------------------- overlays

    function ensureOverlays() {
        var paper = byId(PAPER_ID);
        var band = byId('measure-band');
        if (!band) {
            band = document.createElement('div');
            band.id = 'measure-band';
            band.className = 'overlay';
            paper.appendChild(band);
        }
        var cursor = byId('playhead');
        if (!cursor) {
            cursor = document.createElement('div');
            cursor.id = 'playhead';
            cursor.className = 'overlay';
            var line = document.createElement('div');
            line.className = 'playhead-line';
            cursor.appendChild(line);
            paper.appendChild(cursor);
        }
    }

    function hideOverlays() {
        var band = byId('measure-band');
        var cursor = byId('playhead');
        if (band) band.classList.add('hidden');
        if (cursor) cursor.classList.add('hidden');
    }

    function showAt(measureIndex, beatInMeasure, measureBeats, follow) {
        if (!state.ready) return false;
        var measure = graphicalMeasure(measureIndex);
        if (!measure) return false;
        ensureOverlays();
        var position = beatLeft(measure, beatInMeasure, measureBeats || 4);
        if (!position) return false;
        var band = byId('measure-band');
        var cursor = byId('playhead');
        band.classList.remove('hidden');
        band.style.left = position.box.left + 'px';
        band.style.top = position.box.top + 'px';
        band.style.width = position.box.width + 'px';
        band.style.height = position.box.height + 'px';
        cursor.classList.remove('hidden');
        cursor.style.left = position.left + 'px';
        cursor.style.top = position.top + 'px';
        cursor.style.height = position.height + 'px';
        if (follow) scrollIntoView(position.top);
        state.lastRequest = {
            measure: measureIndex,
            beat: beatInMeasure,
            beats: measureBeats,
            follow: follow === true
        };
        return true;
    }

    function scrollIntoView(topInPaper) {
        var scroller = byId(SCROLLER_ID);
        if (!scroller) return;
        var paper = byId(PAPER_ID);
        var top = paper.offsetTop + topInPaper;
        var viewTop = scroller.scrollTop;
        var viewBottom = viewTop + scroller.clientHeight;
        if (top < viewTop + 24 || top > viewBottom - 80) {
            var target = Math.max(0, top - Math.round(scroller.clientHeight * 0.22));
            if (scroller.scrollTo) {
                scroller.scrollTo({ top: target, behavior: 'smooth' });
            } else {
                scroller.scrollTop = target;
            }
        }
    }

    function restorePosition() {
        if (!state.lastRequest) return;
        var request = state.lastRequest;
        showAt(request.measure, request.beat, request.beats, false);
    }

    // ----------------------------------------------------------- public API

    var api = {
        /** Loads and renders a MusicXML document. */
        loadScore: function (xml, options) {
            try {
                options = options || {};
                state.dark = options.dark !== false;
                state.zoom = typeof options.zoom === 'number' ? options.zoom : 1;
                state.ready = false;
                state.lastRequest = null;
                applyThemeClasses();
                statusText('楽譜を描画しています…');
                var osmd = buildOsmd();
                state.osmd = osmd;
                try {
                    osmd.Zoom = state.zoom;
                } catch (ignored) {
                }
                var target = osmd;
                target.load(xml).then(function () {
                    try {
                        target.render();
                        state.ready = true;
                        statusText('');
                        hideOverlays();
                        var pages = musicPages();
                        var measures = measureList();
                        notify({
                            ok: true,
                            event: 'rendered',
                            measures: measures.length,
                            pages: pages.length,
                            version: (opensheetmusicdisplay && opensheetmusicdisplay.OpenSheetMusicDisplay) ? 'osmd' : 'osmd'
                        });
                    } catch (renderError) {
                        state.ready = false;
                        statusText('楽譜を描画できませんでした');
                        notify({ ok: false, event: 'error', error: String(renderError && renderError.message || renderError) });
                    }
                }, function (loadError) {
                    state.ready = false;
                    statusText('MusicXML を読み込めませんでした');
                    notify({ ok: false, event: 'error', error: String(loadError && loadError.message || loadError) });
                });
                return notify({ ok: true, event: 'loading' });
            } catch (error) {
                state.ready = false;
                statusText('楽譜ビューワーを開始できませんでした');
                return notify({ ok: false, event: 'error', error: String(error && error.message || error) });
            }
        },

        /** Moves the play-head. follow = scroll the page to keep it visible. */
        setPosition: function (measureIndex, beatInMeasure, measureBeats, follow) {
            try {
                return showAt(measureIndex, beatInMeasure, measureBeats, follow === true);
            } catch (error) {
                return false;
            }
        },

        clearPosition: function () {
            hideOverlays();
            state.lastRequest = null;
            return true;
        },

        setTheme: function (dark) {
            state.dark = dark === true;
            applyThemeClasses();
            if (!state.osmd) return true;
            var theme = colors();
            try {
                state.osmd.setOptions({
                    defaultColorMusic: theme.music,
                    defaultColorLabel: theme.label,
                    defaultColorTitle: theme.title,
                    defaultColorNotehead: theme.music,
                    defaultColorStem: theme.music,
                    defaultColorRest: theme.music
                });
                state.osmd.render();
                restorePosition();
                return true;
            } catch (error) {
                return false;
            }
        },

        setZoom: function (zoom) {
            if (typeof zoom !== 'number' || !isFinite(zoom) || zoom <= 0.2) return false;
            state.zoom = zoom;
            if (!state.osmd) return true;
            try {
                state.osmd.Zoom = zoom;
                state.osmd.render();
                restorePosition();
                return true;
            } catch (error) {
                return false;
            }
        },

        status: function () {
            return JSON.stringify({
                ok: state.ready,
                ready: state.ready,
                dark: state.dark,
                zoom: state.zoom,
                measures: measureList().length,
                pages: musicPages().length
            });
        }
    };

    window.PianoSheet = api;

    window.addEventListener('resize', function () {
        if (!state.ready) return;
        window.setTimeout(restorePosition, 120);
    });

    document.addEventListener('DOMContentLoaded', function () {
        applyThemeClasses();
        ensureOverlays();
        hideOverlays();
        notify({ ok: true, event: 'ready' });
    });
}());
