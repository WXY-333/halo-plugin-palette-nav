// Run from the repository root: node tools/test_video_background.cjs
const fs = require('fs');
const vm = require('vm');
const assert = require('assert/strict');
const source = fs.readFileSync('src/main/resources/static/nav/js/script.js', 'utf8');
const utilities = source.slice(source.indexOf('    function storageGet'), source.indexOf('    function trimText'));
const background = source.slice(source.indexOf('    var bgLayer'), source.indexOf('    // ============ 搜索引擎'));

function page({ mobile = false, random = true, saved = null, legacy = false, storageDenied = false, videoConfigured = true } = {}) {
    const data = { backgroundUrl: '/random-image', backgroundFallback: '/cover.jpg',
        backgroundDefault: '/default.jpg', backgroundVideoUrl: videoConfigured ? '/video.mp4' : '', randomBackgroundEnabled: String(random), defaultBackgroundMedia: 'true' };
    const root = { attrs: {}, setAttribute(k, v) { this.attrs[k] = v; },
        removeAttribute(k) { delete this.attrs[k]; }, getAttribute(k) { return this.attrs[k]; } };
    const layer = { style: {}, classList: { add() {}, remove() {} } };
    const images = [], videos = [], listeners = new Map(), timers = new Map();
    let timerId = 0;
    const store = { palette_bgSetting: saved };
    const document = {
        documentElement: root, querySelector: () => layer, querySelectorAll: () => videos.slice(), getElementById: () => null,
        body: { dataset: data, appendChild(v) {
            videos.push(v);
            v.parentNode = { removeChild() { videos.splice(videos.indexOf(v), 1); v.parentNode = null; } };
        } },
        addEventListener(k, fn) { listeners.set(k, fn); },
        removeEventListener(k, fn) { if (listeners.get(k) === fn) listeners.delete(k); },
        createElement() { return { style: {}, attrs: {}, calls: [], pauses: 0,
            setAttribute(k, v) { this.attrs[k] = v; }, getAttribute(k) { return this.attrs[k] || null; }, removeAttribute(k) { delete this[k]; delete this.attrs[k]; },
            pause() { this.pauses++; }, load() {},
            play() {
                const call = {}; this.calls.push(call);
                if (!legacy) return { then(resolve, reject) { call.reject = reject; } };
            } }; }
    };
    const context = { BG_KEY: 'palette_bgSetting', backgroundPreference: undefined,
        navigator: { userAgent: mobile ? 'Android MicroMessenger' : 'Windows' }, document,
        Image: function () { images.push(this); }, window: {
            matchMedia: () => ({ matches: false }),
            setTimeout(fn, delay) { timers.set(++timerId, { fn, delay }); return timerId; }, clearTimeout(id) { timers.delete(id); },
            localStorage: {
                getItem(k) { if (storageDenied) throw Error(); return store[k] ?? null; },
                setItem(k, v) { if (storageDenied) throw Error(); store[k] = v; }
            }
        } };
    vm.createContext(context);
    vm.runInContext(utilities + background, context);
    context.restoreBackground();
    return { context, root, layer, images, videos, listeners, timers, store,
        fire(k, event) { const fn = listeners.get(k); if (fn) fn(event); },
        timeout() { const pending = [...timers.values()]; timers.clear(); pending.forEach(timer => timer.fn()); } };
}

let p = page();
let video = p.videos[0];
assert.equal(video.calls.length, 1);
assert.equal([...p.timers.values()][0].delay, 900, 'video playback is checked within one second');
assert.equal(p.layer.style.backgroundImage, 'none');
assert.equal(p.images.length, 0, 'successful video must not fetch random API');
video.onplaying();
assert.equal(p.layer.style.backgroundImage, 'none');
assert.equal(p.timers.size, 0);
assert.equal(p.store.palette_bgSetting, 'video');

p = page(); video = p.videos[0];
video.calls[0].reject({ name: 'NotAllowedError' });
assert.equal(video.src, undefined, 'rejected autoplay releases the player immediately');
assert.equal(p.images.length, 0);
assert.equal(p.layer.style.backgroundImage, 'none', 'rejected autoplay keeps the theme color');
assert.equal(p.root.attrs['data-background'], undefined);
const failedVideo = video;
p.context.cycleBackground();
assert.equal(p.videos.length, 1);
video = p.videos[0];
assert.notEqual(video, failedVideo, 'first background toggle retries failed autoplay instead of switching off');
assert.equal(video.calls.length, 1, 'button gesture retries playback immediately');
video.onplaying();
assert.equal(p.layer.style.backgroundImage, 'none');
assert.equal(p.listeners.size, 0);
assert.equal(p.timers.size, 0);
assert.equal(failedVideo.parentNode, null, 'failed player is removed before retry');

p = page({ random: false, mobile: true, legacy: true, storageDenied: true }); video = p.videos[0];
p.timeout();
assert.equal(p.layer.style.backgroundImage, 'none', 'silent playback timeout keeps the theme color');
assert.equal(p.root.attrs['data-background'], undefined);
p.fire('click');
p.timeout(); p.fire('click');
assert.equal(video.calls.length, 2);
assert.equal(p.timers.size, 0);
assert.equal(p.listeners.size, 0);

p = page(); video = p.videos[0];
p.timeout(); p.fire('click');
const firstAttempt = video.calls[0];
firstAttempt.reject({ name: 'NotAllowedError' });
assert.equal(video.src, '/video.mp4', 'old Promise cannot cancel a newer attempt');
video.calls[1].reject({ name: 'NotAllowedError' });
assert.equal(p.listeners.size, 0);
assert.equal(p.store.palette_bgSetting, 'video', 'automatic fallback must not change preference');

p = page(); video = p.videos[0];
video.calls[0].reject({ name: 'NotAllowedError' });
p.context.cycleBackground();
assert.equal(p.videos.length, 1, 'toggle retries the failed player');
assert.notEqual(p.videos[0], video);
p.context.clearBackground();
assert.equal(p.videos.length, 0);
assert.equal(p.listeners.size, 0);
assert.equal(p.timers.size, 0);
assert.equal(p.layer.style.backgroundImage, 'none');
assert.equal(p.store.palette_bgSetting, 'none');
video.calls[0].reject({ name: 'NotAllowedError' });
assert.equal(p.listeners.size, 0);

for (const saved of [null, 'video', 'image']) {
    p = page({ mobile: true, saved });
    assert.equal(p.videos.length, 1, 'mobile also prefers video over API');
    assert.equal(p.layer.style.backgroundImage, 'none');
    p.videos[0].onerror();
    assert.equal(p.layer.style.backgroundImage, 'none', 'video error keeps the theme color');
    assert.equal(p.root.attrs['data-background'], undefined);
    assert.equal(p.images.length, 0, 'video fallback does not request API');
}
p = page({ videoConfigured: false });
assert.equal(p.videos.length, 0);
assert.equal(p.layer.style.backgroundImage, 'none', 'API loads over theme color');
const apiImage = p.images.find(i => i.src === '/random-image');
assert(apiImage);
apiImage.onload();
assert(p.layer.style.backgroundImage.includes('/random-image'));
const selectedImage = p.images.find(i => i.src === '/cover.jpg');
assert.equal(selectedImage, undefined, 'selected image is not shown while API is loading');
apiImage.onerror();
const fallbackImage = p.images.find(i => i.src === '/cover.jpg');
assert(fallbackImage);
fallbackImage.onload();
assert(p.layer.style.backgroundImage.includes('/cover.jpg'));
fallbackImage.onerror();
const defaultImage = p.images.find(i => i.src === '/default.jpg');
assert(defaultImage);
defaultImage.onload();
assert(p.layer.style.backgroundImage.includes('/default.jpg'));
p = page({ videoConfigured: false });
const lateImage = p.images.find(i => i.src === '/random-image').onload;
p.context.clearBackground(); lateImage();
assert.equal(p.layer.style.backgroundImage, 'none');
p = page({ saved: 'none' });
assert.equal(p.videos.length, 0);
assert.equal(p.images.length, 0);
console.log('PASS: video success, autoplay rejection, single gesture retry, timeout, legacy WebView, cleanup, stale callbacks, mobile priority and preferences');
