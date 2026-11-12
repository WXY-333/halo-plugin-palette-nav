/**
 * Palette Nav - UI Interactions
 * 配色、夜间模式、时钟、背景切换、搜索引擎、搜索建议、分类筛选
 *
 * 原生 ES5，无 jQuery / 无框架依赖。
 * 服务端配置通过 <body> 的 data-* 属性注入。
 * 搜索区 DOM 契约：form#search-form / input#search-input /
 * input[name="sou"][data-alias][data-hint] / localStorage['theme_sou']
 * 分类栏 DOM 契约：.category-nav > .category-scroll + .category-arrow[data-scroll-dir]
 * 封面预览：body[data-hover-preview][data-preview-loading-image] / .link-card .link-cover-data[data-cover-url]
 */
(function () {
    'use strict';

    var COLOR_KEY = 'palette_color';
    var NIGHT_KEY = 'palette_nightMode';
    var BG_KEY = 'palette_bgSetting';
    var backgroundPreference;
    var SOU_KEY = 'theme_sou';

    var colors = ['purple', 'blue', 'green', 'pink', 'orange', 'teal'];

    // ============ 通用工具 ============
    function storageGet(key) {
        if (key === BG_KEY && backgroundPreference !== undefined) return backgroundPreference;
        try {
            return window.localStorage.getItem(key);
        } catch (e) {
            return null;
        }
    }

    function storageSet(key, value) {
        if (key === BG_KEY) backgroundPreference = value;
        try {
            window.localStorage.setItem(key, value);
        } catch (e) {
            // 隐私模式等场景写入失败，静默降级
        }
    }

    function bodyData(key) {
        if (!document.body || !document.body.dataset) {
            return '';
        }
        var value = document.body.dataset[key];
        return value == null ? '' : String(value);
    }

    function trimText(value) {
        return String(value == null ? '' : value).replace(/^\s+|\s+$/g, '');
    }

    function pad2(value) {
        return value < 10 ? '0' + value : '' + value;
    }

    function containsColor(color) {
        for (var i = 0; i < colors.length; i++) {
            if (colors[i] === color) {
                return true;
            }
        }
        return false;
    }

    // ============ 主题色彩 ============
    function updateColorActive(color) {
        var items = document.querySelectorAll('.color-opt');
        for (var i = 0; i < items.length; i++) {
            if (items[i].getAttribute('data-color') === color) {
                items[i].classList.add('active');
            } else {
                items[i].classList.remove('active');
            }
        }
    }

    function setColor(color) {
        if (!containsColor(color)) {
            color = 'purple';
        }
        document.documentElement.setAttribute('data-theme', color);
        storageSet(COLOR_KEY, color);
        updateColorActive(color);
    }

    // ============ 夜间模式 ============
    function updateNightModeIcon() {
        var isNight = document.documentElement.classList.contains('night-mode');
        var sunIcon = document.querySelector('#nightBtn .icon-sun');
        var moonIcon = document.querySelector('#nightBtn .icon-moon');
        if (sunIcon && moonIcon) {
            sunIcon.style.display = isNight ? 'none' : '';
            moonIcon.style.display = isNight ? '' : 'none';
        }
        var nightBtn = document.getElementById('nightBtn');
        if (nightBtn) {
            nightBtn.setAttribute('aria-pressed', isNight ? 'true' : 'false');
        }
    }

    // ============ 时钟与问候 ============
    function updateClock() {
        var now = new Date();
        var timeEl = document.getElementById('clockTime');
        var dateEl = document.getElementById('clockDate');
        var greetingEl = document.getElementById('clockGreeting');

        if (timeEl) {
            timeEl.textContent = pad2(now.getHours()) + ':' + pad2(now.getMinutes()) + ':' + pad2(now.getSeconds());
        }

        if (dateEl) {
            var weekdays = ['星期日', '星期一', '星期二', '星期三', '星期四', '星期五', '星期六'];
            dateEl.textContent = now.getFullYear() + '年' + (now.getMonth() + 1) + '月' + now.getDate() + '日 ' + weekdays[now.getDay()];
        }

        if (greetingEl) {
            var h = now.getHours();
            var greeting;
            if (h < 6) {
                greeting = '夜深了，早点休息吧';
            } else if (h < 9) {
                greeting = '早上好！新的一天开始了';
            } else if (h < 12) {
                greeting = '上午好！愿你精力充沛';
            } else if (h < 14) {
                greeting = '中午好！记得按时吃饭';
            } else if (h < 18) {
                greeting = '下午好！工作辛苦了';
            } else if (h < 22) {
                greeting = '晚上好！放松一下吧';
            } else {
                greeting = '夜深了，晚安';
            }
            greetingEl.textContent = greeting;
        }
    }

    // ============ 背景切换 ============
    var bgLayer = document.querySelector('.bg-layer');
    var resolvedBackground = '';
    var backgroundChecked = false;
    var backgroundLoadRevision = 0;
    var videoBackgroundCleanup = null;
    var videoBackgroundState = 'idle';

    function applyResolvedBackground(url) {
        resolvedBackground = url;
        // A late response must respect a visitor who has since disabled the background.
        if (document.documentElement.getAttribute('data-background') === 'image' && bgLayer) {
            bgLayer.style.backgroundImage = 'url("' + url.replace(/"/g, '%22') + '")';
            document.documentElement.style.setProperty('--pn-bg-image',
                'url("' + url.replace(/"/g, '%22') + '")');
        }
    }

    function checkRandomBackground() {
        var resolver = bodyData('backgroundResolver');
        if (backgroundChecked || !resolver) return;
        backgroundChecked = true;
        var xhr = new XMLHttpRequest();
        xhr.open('GET', resolver, true);
        xhr.timeout = 15000;
        xhr.onload = function () {
            var url = trimText(xhr.responseText);
            if (xhr.status !== 200 || !/^(https?:\/\/|\/(?!\/))/i.test(url)
                || /[\\\x00-\x1f\x7f]/.test(url)) return;
            var image = new Image();
            image.onload = function () { applyResolvedBackground(url); };
            image.src = url;
        };
        xhr.send();
    }

    function removeVideoBackground() {
        if (videoBackgroundCleanup) {
            videoBackgroundCleanup();
            videoBackgroundCleanup = null;
        }
        var videos = document.querySelectorAll('body > video');
        for (var i = 0; i < videos.length; i++) {
            if (videos[i].parentNode) {
                videos[i].onerror = null;
                videos[i].onplaying = null;
                videos[i].pause();
                videos[i].removeAttribute('src');
                videos[i].load();
                videos[i].parentNode.removeChild(videos[i]);
            }
        }
    }

    function backgroundImageLayers(urls) {
        var layers = [];
        for (var i = 0; i < urls.length; i++) {
            if (urls[i]) layers.push('url("' + urls[i].replace(/"/g, '%22') + '")');
        }
        return layers.length ? layers.join(', ') : 'none';
    }

    function setImageBackground() {
        var hasRandomApi = bodyData('randomBackgroundEnabled') === 'true';
        var url = hasRandomApi ? bodyData('backgroundUrl') : resolvedBackground || bodyData('backgroundUrl');
        if (!bgLayer || !url) return false;
        storageSet(BG_KEY, 'image');
        var revision = ++backgroundLoadRevision;
        removeVideoBackground();
        var fallback = bodyData('backgroundFallback');
        var defaultImage = bodyData('backgroundDefault');
        var fallbackImage = fallback && fallback !== url ? fallback : '';
        bgLayer.classList.remove('image-bg');
        bgLayer.style.backgroundImage = 'none';
        document.documentElement.removeAttribute('data-background');
        if (!hasRandomApi) {
            bgLayer.classList.add('image-bg');
            document.documentElement.setAttribute('data-background', 'image');
            bgLayer.style.backgroundImage = backgroundImageLayers([url, defaultImage]);
        }
        var requestedImage = new Image();
        requestedImage.onload = function () {
            if (revision === backgroundLoadRevision) {
                bgLayer.classList.add('image-bg');
                document.documentElement.setAttribute('data-background', 'image');
                bgLayer.style.backgroundImage = backgroundImageLayers([url]);
            }
        };
        requestedImage.onerror = function () {
            if (revision === backgroundLoadRevision) {
                function showFallbackImage(imageUrl) {
                    if (revision !== backgroundLoadRevision || !imageUrl) return;
                    var probe = new Image();
                    probe.onload = function () {
                        if (revision !== backgroundLoadRevision) return;
                        bgLayer.classList.add('image-bg');
                        document.documentElement.setAttribute('data-background', 'image');
                        bgLayer.style.backgroundImage = backgroundImageLayers([imageUrl, defaultImage]);
                    };
                    probe.onerror = function () {
                        if (imageUrl !== defaultImage) showFallbackImage(defaultImage);
                    };
                    probe.src = imageUrl;
                }
                showFallbackImage(fallbackImage || defaultImage);
            }
        };
        requestedImage.src = url;
        checkRandomBackground();
        return true;
    }

    function setVideoBackground() {
        var src = bodyData('backgroundVideoUrl');
        if (!src) {
            return false;
        }
        storageSet(BG_KEY, 'video');
        videoBackgroundState = 'waiting';
        var revision = ++backgroundLoadRevision;
        removeVideoBackground();
        var video = document.createElement('video');
        video.loop = true;
        video.muted = true;
        video.defaultMuted = true;
        video.autoplay = true;
        video.preload = 'auto';
        video.playsInline = true;
        video.setAttribute('autoplay', '');
        video.setAttribute('preload', 'auto');
        video.setAttribute('muted', '');
        video.setAttribute('playsinline', '');
        video.setAttribute('webkit-playsinline', '');
        video.style.cssText = 'position:fixed;top:0;left:0;width:100%;height:100%;object-fit:cover;z-index:-3;pointer-events:none;';
        var disposed = false;
        var state = 'waiting';
        var retried = false;
        var playbackTimer = null;
        var playbackAttempt = 0;

        function active() {
            return !disposed && revision === backgroundLoadRevision;
        }

        function clearPlaybackTimer() {
            if (playbackTimer !== null) window.clearTimeout(playbackTimer);
            playbackTimer = null;
        }

        function removeRetryListeners() {
            document.removeEventListener('click', retryPlayback, true);
            document.removeEventListener('touchend', retryPlayback, true);
            document.removeEventListener('keydown', retryPlayback, true);
        }

        function showFallback(url) {
            if (!active() || !bgLayer || !url || state === 'playing') return;
            document.documentElement.setAttribute('data-background', 'image');
            bgLayer.classList.add('image-bg');
            bgLayer.style.backgroundImage = 'url("' + url.replace(/"/g, '%22') + '")';
        }

        function playbackFailed(allowRetry) {
            if (!active() || state === 'failed' || state === 'playing') return;
            state = 'failed';
            videoBackgroundState = 'failed';
            clearPlaybackTimer();
            video.onerror = null;
            video.onplaying = null;
            video.pause();
            video.removeAttribute('src');
            video.load();
            // Video failure stays on the page theme; do not flash a late fallback image.
            document.documentElement.removeAttribute('data-background');
            if (bgLayer) {
                bgLayer.classList.remove('image-bg');
                bgLayer.style.backgroundImage = 'none';
            }
            if (allowRetry && !retried) {
                document.addEventListener('click', retryPlayback, true);
                document.addEventListener('touchend', retryPlayback, true);
                document.addEventListener('keydown', retryPlayback, true);
            }
        }

        function attemptPlayback() {
            if (!active()) return;
            clearPlaybackTimer();
            state = 'waiting';
            var attempt = ++playbackAttempt;
            video.onerror = function () {
                if (state === 'playing') state = 'waiting';
                playbackFailed(false);
            };
            video.onplaying = function () {
                if (!active() || state !== 'waiting') return;
                state = 'playing';
                videoBackgroundState = 'playing';
                clearPlaybackTimer();
                removeRetryListeners();
                document.documentElement.removeAttribute('data-background');
                if (bgLayer) {
                    bgLayer.classList.remove('image-bg');
                    bgLayer.style.backgroundImage = 'none';
                }
            };
            // Give autoplay a brief chance to produce a playing event, then keep the theme color.
            playbackTimer = window.setTimeout(function () { playbackFailed(true); }, 900);
            video.src = src;
            try {
                var playResult = video.play();
                if (playResult && typeof playResult.then === 'function') {
                    playResult.then(null, function (error) {
                        if (attempt !== playbackAttempt) return;
                        playbackFailed(!error || error.name === 'NotAllowedError');
                    });
                }
            } catch (error) {
                playbackFailed(!error || error.name === 'NotAllowedError');
            }
        }

        function retryPlayback(event) {
            // The background toggle must only switch modes, never start a retry first.
            var target = event && event.target;
            while (target && target !== document) {
                if (target.id === 'bgBtn') return;
                target = target.parentNode;
            }
            removeRetryListeners();
            if (!active() || retried || state !== 'failed') return;
            retried = true;
            attemptPlayback();
        }

        videoBackgroundCleanup = function () {
            disposed = true;
            if (revision === backgroundLoadRevision) videoBackgroundState = 'idle';
            clearPlaybackTimer();
            removeRetryListeners();
        };
        // Start on the selected theme color; only failed playback needs an image.
        document.documentElement.removeAttribute('data-background');
        if (bgLayer) {
            bgLayer.classList.remove('image-bg');
            bgLayer.style.backgroundImage = 'none';
        }
        if (!video.parentNode) document.body.appendChild(video);
        attemptPlayback();
        return true;
    }

    function clearBackground() {
        backgroundLoadRevision += 1;
        videoBackgroundState = 'idle';
        storageSet(BG_KEY, 'none');
        document.documentElement.removeAttribute('data-background');
        if (bgLayer) {
            bgLayer.classList.remove('image-bg');
            bgLayer.style.backgroundImage = 'none';
        }
        removeVideoBackground();
    }

    // 后台是否配置了视频背景地址
    function hasVideoBackground() {
        return !!bodyData('backgroundVideoUrl');
    }

    function restoreBackground() {
        var saved = storageGet(BG_KEY);
        // 仅在没有个人偏好时应用站点默认背景。
        if (saved === null && bodyData('defaultBackgroundMedia') === 'true') {
            if (hasVideoBackground()) {
                setVideoBackground();
            } else {
                setImageBackground();
            }
            return;
        }
        if (saved === 'video') {
            if (!setVideoBackground()) setImageBackground();
        } else if (saved === 'image') {
            if (hasVideoBackground()) setVideoBackground();
            else setImageBackground();
        }
    }

    function cycleBackground() {
        var current = storageGet(BG_KEY);
        // 配置了视频地址：只切换 视频 / 无背景；未配置：只切换 图片 / 无背景
        if (hasVideoBackground()) {
            if (current === 'video') {
                if (videoBackgroundState === 'failed') setVideoBackground();
                else clearBackground();
            } else {
                setVideoBackground();
            }
            return;
        }
        if (current === 'image') {
            clearBackground();
        } else {
            setImageBackground();
        }
    }

    // ============ 搜索引擎 ============
    var searchForm = document.getElementById('search-form');
    var searchInput = document.getElementById('search-input');
    var searchSwitcher = document.getElementById('engineSwitcher');
    var engineIcon = document.getElementById('engineIcon');
    var engineOptions = document.getElementById('engineOptions');
    var suggestionList = document.getElementById('searchSuggestions');
    var engines = document.querySelectorAll('input[name="sou"]');

    var suggestTimer = null;
    var suggestScript = null;
    var suggestCallbackName = null;

    function currentEngine() {
        for (var i = 0; i < engines.length; i++) {
            if (engines[i].checked) {
                return engines[i];
            }
        }
        return engines.length ? engines[0] : null;
    }

    function currentAlias() {
        var engine = currentEngine();
        return engine ? (engine.getAttribute('data-alias') || '') : '';
    }

    function hideSuggestions() {
        if (!suggestionList) {
            return;
        }
        suggestionList.className = 'search-suggestions';
        while (suggestionList.firstChild) {
            suggestionList.removeChild(suggestionList.firstChild);
        }
    }

    function cleanupSuggestion() {
        if (suggestTimer) {
            clearTimeout(suggestTimer);
            suggestTimer = null;
        }
        if (suggestScript && suggestScript.parentNode) {
            suggestScript.parentNode.removeChild(suggestScript);
        }
        suggestScript = null;
        if (suggestCallbackName) {
            var name = suggestCallbackName;
            suggestCallbackName = null;
            // 已发起但尚未执行的 JSONP 仍可能回调，先替换为空函数再延迟删除，
            // 避免控制台出现 “paletteSuggest... is not defined”
            window[name] = function () {};
            setTimeout(function () {
                try {
                    delete window[name];
                } catch (e) {
                    window[name] = undefined;
                }
            }, 10000);
        }
    }

    function clearSuggestions() {
        cleanupSuggestion();
        hideSuggestions();
    }

    function updateEngineUI() {
        var engine = currentEngine();
        if (!engine) {
            return;
        }

        var label = engine.parentNode;
        var iconSpan = label && label.querySelector ? label.querySelector('.engine-option-icon') : null;
        if (engineIcon && iconSpan) {
            engineIcon.innerHTML = iconSpan.innerHTML;
        }

        var labels = document.querySelectorAll('.engine-option');
        for (var i = 0; i < labels.length; i++) {
            var input = labels[i].querySelector ? labels[i].querySelector('input[name="sou"]') : null;
            if (input === engine) {
                labels[i].classList.add('active');
            } else {
                labels[i].classList.remove('active');
            }
        }

        if (searchInput) {
            searchInput.placeholder = engine.getAttribute('data-hint') || '请输入搜索内容';
        }
        clearSuggestions();
    }

    function isEngineMenuOpen() {
        return !!(engineOptions && engineOptions.classList.contains('show'));
    }

    function setEngineMenu(open) {
        if (engineOptions) {
            if (open) {
                engineOptions.classList.add('show');
            } else {
                engineOptions.classList.remove('show');
            }
        }
        if (searchSwitcher) {
            if (open) {
                searchSwitcher.classList.add('open');
            } else {
                searchSwitcher.classList.remove('open');
            }
            searchSwitcher.setAttribute('aria-expanded', open ? 'true' : 'false');
        }
        if (open) {
            clearSuggestions();
            var engine = currentEngine();
            if (engine && engine.focus) {
                try {
                    engine.focus();
                } catch (e) {
                    // 忽略聚焦失败
                }
            }
        }
    }

    function toggleEngineMenu() {
        setEngineMenu(!isEngineMenuOpen());
    }

    function restoreEngine() {
        var saved = storageGet(SOU_KEY);
        if (saved) {
            for (var i = 0; i < engines.length; i++) {
                if (engines[i].getAttribute('data-alias') === saved) {
                    engines[i].checked = true;
                    break;
                }
            }
        }
        updateEngineUI();
    }

    function doSearch(keyword) {
        var engine = currentEngine();
        keyword = trimText(keyword);
        var engineUrl = engine ? trimText(engine.value) : '';
        // 搜索地址为空时不做任何跳转，避免拼出站内相对 URL
        if (!engineUrl || !keyword) {
            return;
        }
        var win = window.open(engineUrl + encodeURIComponent(keyword), '_blank');
        if (win) {
            win.opener = null;
        }
    }

    function renderSuggestions(data) {
        if (!suggestionList) {
            return;
        }
        while (suggestionList.firstChild) {
            suggestionList.removeChild(suggestionList.firstChild);
        }
        if (!data || !data.s || !data.s.length) {
            suggestionList.className = 'search-suggestions';
            return;
        }
        for (var i = 0; i < data.s.length; i++) {
            var item = document.createElement('li');
            item.innerHTML = '<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>';
            item.appendChild(document.createTextNode(String(data.s[i])));
            suggestionList.appendChild(item);
        }
        suggestionList.className = 'search-suggestions show';
    }

    function fetchSuggestions(keyword) {
        if (bodyData('baiduSuggest') !== 'true') {
            hideSuggestions();
            return;
        }
        if (currentAlias() !== 'baidu' || !suggestionList) {
            hideSuggestions();
            return;
        }
        cleanupSuggestion();

        var callbackName = 'paletteSuggest' + (new Date().getTime()) + Math.floor(Math.random() * 1000);
        var script = document.createElement('script');

        window[callbackName] = function (data) {
            // 已被后续请求取代时丢弃迟到的结果
            if (suggestCallbackName !== callbackName) {
                return;
            }
            cleanupSuggestion();
            renderSuggestions(data);
        };

        script.src = 'https://suggestion.baidu.com/su?wd=' + encodeURIComponent(keyword) + '&cb=' + callbackName;
        script.onerror = function () {
            // 只清理本次请求，避免误删已被后续请求替换的 script
            if (suggestScript !== script) {
                return;
            }
            cleanupSuggestion();
            hideSuggestions();
        };

        suggestCallbackName = callbackName;
        suggestScript = script;
        document.body.appendChild(script);
    }

    function bindSearch() {
        if (searchSwitcher) {
            searchSwitcher.addEventListener('click', function (e) {
                e.stopPropagation();
                toggleEngineMenu();
            });
            searchSwitcher.addEventListener('keydown', function (e) {
                var key = e.key || e.keyCode;
                if (key === 'Enter' || key === ' ' || key === 13 || key === 32) {
                    e.preventDefault();
                    toggleEngineMenu();
                } else if (key === 'ArrowDown' || key === 40) {
                    e.preventDefault();
                    setEngineMenu(true);
                } else if (key === 'Escape' || key === 27) {
                    setEngineMenu(false);
                }
            });
        }

        if (engineOptions) {
            engineOptions.addEventListener('click', function (e) {
                // 点击选项时不触发 document 的“点击外部关闭”
                e.stopPropagation();

                if (!e.target.closest('.engine-option')) {
                    return;
                }
                // 选中状态、偏好写入与 UI 刷新统一交给 radio 的 change 事件，
                // 这里只负责关闭菜单（含重复点击已选中项的场景）
                setEngineMenu(false);
            });

            engineOptions.addEventListener('keydown', function (e) {
                var key = e.key || e.keyCode;
                var count = engines.length;
                if (!count) {
                    return;
                }

                if (key === 'ArrowDown' || key === 'ArrowUp' || key === 40 || key === 38) {
                    e.preventDefault();
                    var index = -1;
                    for (var k = 0; k < count; k++) {
                        if (engines[k].checked) {
                            index = k;
                            break;
                        }
                    }
                    if (index < 0) {
                        index = 0;
                    } else {
                        index = (index + ((key === 'ArrowDown' || key === 40) ? 1 : count - 1)) % count;
                    }
                    engines[index].checked = true;
                    storageSet(SOU_KEY, engines[index].getAttribute('data-alias') || '');
                    updateEngineUI();
                    if (engines[index].focus) {
                        engines[index].focus();
                    }
                } else if (key === 'Enter' || key === ' ' || key === 13 || key === 32) {
                    e.preventDefault();
                    var current = currentEngine();
                    if (current) {
                        storageSet(SOU_KEY, current.getAttribute('data-alias') || '');
                        updateEngineUI();
                    }
                    setEngineMenu(false);
                    if (searchInput) {
                        searchInput.focus();
                    }
                } else if (key === 'Escape' || key === 27) {
                    e.preventDefault();
                    setEngineMenu(false);
                    if (searchSwitcher) {
                        searchSwitcher.focus();
                    }
                }
            });
        }

        for (var i = 0; i < engines.length; i++) {
            engines[i].addEventListener('change', function () {
                storageSet(SOU_KEY, this.getAttribute('data-alias') || '');
                updateEngineUI();
                setEngineMenu(false);
            });
        }

        if (searchForm) {
            searchForm.addEventListener('submit', function (e) {
                e.preventDefault();
                var keyword = searchInput ? trimText(searchInput.value) : '';
                if (!keyword) {
                    if (searchInput) {
                        searchInput.focus();
                    }
                    return;
                }
                doSearch(keyword);
                clearSuggestions();
            });
        }

        if (searchInput) {
            searchInput.addEventListener('input', function () {
                var keyword = trimText(this.value);
                if (suggestTimer) {
                    clearTimeout(suggestTimer);
                    suggestTimer = null;
                }
                if (!keyword) {
                    clearSuggestions();
                    return;
                }
                suggestTimer = setTimeout(function () {
                    fetchSuggestions(keyword);
                }, 250);
            });
        }

        if (suggestionList) {
            suggestionList.addEventListener('click', function (e) {
                var node = e.target;
                while (node && node !== suggestionList && (!node.tagName || node.tagName.toLowerCase() !== 'li')) {
                    node = node.parentNode;
                }
                if (!node || node === suggestionList) {
                    return;
                }
                var word = trimText(node.textContent || node.innerText || '');
                if (word && searchInput) {
                    searchInput.value = word;
                    clearSuggestions();
                    doSearch(word);
                }
            });
        }
    }

    // ============ 分类筛选 ============
    function filterCategory(categoryId) {
        var groups = document.querySelectorAll('.link-group');
        for (var i = 0; i < groups.length; i++) {
            var groupId = groups[i].getAttribute('data-group-id');
            groups[i].style.display = (categoryId === '0' || groupId === categoryId) ? '' : 'none';
        }
    }

    // ============ 分类栏横向滚动 ============
    // .category-scroll 隐藏了滚动条：触屏可原生滑动，但桌面鼠标既看不到滚动条，
    // 竖向滚轮也不会转成横向滚动，分类较多时后半部分无法访问。
    // 这里补：滚轮→横向滚动、按住拖动、两侧箭头、边缘渐隐。
    function setClass(node, name, enabled) {
        if (!node) {
            return;
        }
        if (enabled) {
            node.classList.add(name);
        } else {
            node.classList.remove(name);
        }
    }

    function prefersReducedMotion() {
        try {
            return !!(window.matchMedia
                && window.matchMedia('(prefers-reduced-motion: reduce)').matches);
        } catch (e) {
            return false;
        }
    }

    function scrollScrollerBy(scroller, amount) {
        if (!scroller || !amount) {
            return;
        }
        if (prefersReducedMotion() || typeof scroller.scrollBy !== 'function') {
            scroller.scrollLeft += amount;
            return;
        }
        try {
            scroller.scrollBy({ left: amount, behavior: 'smooth' });
        } catch (e) {
            scroller.scrollLeft += amount;
        }
    }

    // 让选中的分类尽量居中，避免它在可视区边缘或完全滑出
    function scrollCategoryIntoView(item) {
        var scroller = document.querySelector('.category-nav .category-scroll');
        if (!item || !scroller) {
            return;
        }
        var scrollerRect = scroller.getBoundingClientRect();
        var itemRect = item.getBoundingClientRect();
        if (itemRect.left >= scrollerRect.left && itemRect.right <= scrollerRect.right) {
            return;
        }
        scrollScrollerBy(scroller, (itemRect.left - scrollerRect.left)
            - (scrollerRect.width - itemRect.width) / 2);
    }

    function bindCategoryScroll() {
        var nav = document.querySelector('.category-nav');
        if (!nav) {
            return;
        }
        var scroller = nav.querySelector('.category-scroll');
        if (!scroller) {
            return;
        }
        var EPS = 2;
        var drag = null;
        var suppressClick = false;

        function updateState() {
            var max = scroller.scrollWidth - scroller.clientWidth;
            var scrollable = max > EPS;
            var atStart = scroller.scrollLeft <= EPS;
            var atEnd = scroller.scrollLeft >= max - EPS;
            setClass(nav, 'is-scrollable', scrollable);
            setClass(nav, 'at-start', atStart);
            setClass(nav, 'at-end', atEnd);
            setClass(nav, 'can-scroll-left', scrollable && !atStart);
            setClass(nav, 'can-scroll-right', scrollable && !atEnd);
        }

        function onWheel(e) {
            if (scroller.scrollWidth <= scroller.clientWidth + EPS) {
                return;
            }
            // 触控板/横向滚轮已经能横滚，交回浏览器处理
            if (Math.abs(e.deltaX) > Math.abs(e.deltaY)) {
                return;
            }
            var unit = e.deltaMode === 1 ? 16 : (e.deltaMode === 2 ? scroller.clientWidth : 1);
            var max = scroller.scrollWidth - scroller.clientWidth;
            var next = scroller.scrollLeft + e.deltaY * unit;
            if (next < 0) {
                next = 0;
            } else if (next > max) {
                next = max;
            }
            if (Math.abs(next - scroller.scrollLeft) < 1) {
                return;
            }
            e.preventDefault();
            scroller.scrollLeft = next;
        }

        function onMouseDown(e) {
            if (e.button !== 0 || scroller.scrollWidth <= scroller.clientWidth + EPS) {
                return;
            }
            drag = { startX: e.clientX, startLeft: scroller.scrollLeft, moved: false };
            // 阻止拖动时的文本选中与原生图片拖拽
            e.preventDefault();
        }

        function onMouseMove(e) {
            if (!drag) {
                return;
            }
            var dx = e.clientX - drag.startX;
            if (!drag.moved) {
                if (Math.abs(dx) < 5) {
                    return;
                }
                drag.moved = true;
                scroller.classList.add('is-dragging');
            }
            scroller.scrollLeft = drag.startLeft - dx;
        }

        function onMouseUp() {
            if (!drag) {
                return;
            }
            var moved = drag.moved;
            drag = null;
            scroller.classList.remove('is-dragging');
            if (moved) {
                // 拖动结束后浏览器还会派发 click，抑制它以免误切分类
                suppressClick = true;
                setTimeout(function () {
                    suppressClick = false;
                }, 0);
            }
        }

        scroller.addEventListener('wheel', onWheel);
        scroller.addEventListener('mousedown', onMouseDown);
        document.addEventListener('mousemove', onMouseMove);
        document.addEventListener('mouseup', onMouseUp);
        scroller.addEventListener('click', function (e) {
            if (!suppressClick) {
                return;
            }
            suppressClick = false;
            e.preventDefault();
            e.stopPropagation();
        }, true);
        scroller.addEventListener('scroll', updateState);
        window.addEventListener('resize', updateState);
        window.addEventListener('load', updateState);
        updateState();

        var arrows = nav.querySelectorAll('.category-arrow');
        for (var i = 0; i < arrows.length; i++) {
            (function (arrow) {
                arrow.addEventListener('click', function () {
                    var dir = arrow.getAttribute('data-scroll-dir') === 'prev' ? -1 : 1;
                    scrollScrollerBy(scroller, dir * Math.max(160, Math.round(scroller.clientWidth * 0.7)));
                });
            }(arrows[i]));
        }

        // 字体/图标加载会改变标签宽度，需要重新判断是否溢出
        if (typeof window.ResizeObserver === 'function') {
            try {
                new window.ResizeObserver(updateState).observe(scroller);
            } catch (e) {
                // 忽略不支持的情况，已有 resize / load 兜底
            }
        }
    }

    function bindCategories() {
        var items = document.querySelectorAll('.category-item');
        for (var i = 0; i < items.length; i++) {
            items[i].addEventListener('click', function () {
                var categoryId = this.getAttribute('data-cat') || '0';
                var allItems = document.querySelectorAll('.category-item');
                for (var j = 0; j < allItems.length; j++) {
                    allItems[j].classList.remove('active');
                    allItems[j].setAttribute('aria-pressed', 'false');
                }
                this.classList.add('active');
                this.setAttribute('aria-pressed', 'true');
                filterCategory(categoryId);
                scrollCategoryIntoView(this);
            });
        }
    }

    // ============ 链接主页封面预览 ============
    // 一个共用浮层，只有鼠标停留后才请求图片，不影响触屏点击与页面初次加载。
    function bindHoverPreview() {
        if (bodyData('hoverPreview') !== 'true' || !window.matchMedia) {
            return;
        }
        var hoverMedia = window.matchMedia('(hover: hover) and (pointer: fine)');
        var popup = document.createElement('div');
        popup.id = 'link-hover-preview';
        popup.className = 'link-hover-preview';
        popup.setAttribute('role', 'tooltip');
        popup.setAttribute('aria-hidden', 'true');
        var loading = document.createElement('div');
        loading.className = 'link-preview-loading';
        var spinner = document.createElement('span');
        spinner.className = 'link-preview-spinner';
        spinner.setAttribute('aria-hidden', 'true');
        loading.appendChild(spinner);
        var loadingUrl = bodyData('previewLoadingImage');
        var loadingImage = document.createElement('img');
        loadingImage.alt = '';
        loadingImage.className = 'link-preview-loading-image';
        loadingImage.hidden = true;
        // 自定义动图仅在第一次实际悬停时加载；失效时回退默认动画。
        loadingImage.onload = function () {
            spinner.hidden = true;
            loadingImage.hidden = false;
            loading.classList.add('has-image');
        };
        loadingImage.onerror = function () {
            loadingImage.hidden = true;
            spinner.hidden = false;
            loading.classList.remove('has-image');
        };
        loading.appendChild(loadingImage);
        var status = document.createElement('span');
        loading.appendChild(status);
        popup.appendChild(loading);
        document.body.appendChild(popup);
        var current = null;
        var pending = null;
        var delayTimer = null;
        var timeoutTimer = null;
        var sequence = 0;
        var cover = null;

        function close() {
            if (!current) {
                return;
            }
            sequence += 1;
            clearTimeout(delayTimer);
            clearTimeout(timeoutTimer);
            if (pending) {
                pending.onload = pending.onerror = null;
                pending = null;
            }
            if (current) {
                current.removeAttribute('aria-describedby');
            }
            current = null;
            popup.classList.remove('is-visible');
            popup.setAttribute('aria-hidden', 'true');
        }

        function place(card) {
            var rect = card.getBoundingClientRect();
            var width = popup.offsetWidth;
            var height = popup.offsetHeight;
            var left = Math.max(12, Math.min(rect.left + rect.width / 2 - width / 2,
                window.innerWidth - width - 12));
            var top = rect.top - height - 12;
            if (top < 12) {
                top = rect.bottom + 12;
            }
            top = Math.max(12, Math.min(top, window.innerHeight - height - 12));
            popup.style.left = left + 'px';
            popup.style.top = top + 'px';
        }

        function show(card) {
            if (!hoverMedia.matches) {
                return;
            }
            close();
            var coverData = card.querySelector('.link-cover-data');
            if (!coverData || !trimText(coverData.getAttribute('data-cover-url'))) {
                return;
            }
            current = card;
            var token = sequence;
            delayTimer = setTimeout(function () {
                if (token !== sequence || !hoverMedia.matches) {
                    return;
                }
                if (cover) {
                    popup.removeChild(cover);
                    cover = null;
                }
                popup.classList.remove('is-loaded');
                popup.classList.remove('is-error');
                status.textContent = '正在加载封面…';
                spinner.hidden = false;
                loadingImage.hidden = true;
                loading.classList.remove('has-image');
                var data = card.querySelector('.link-cover-data');
                var url = data ? data.getAttribute('data-cover-url') : '';
                if (url) {
                    if (loadingUrl) {
                        if (loadingImage.getAttribute('src') !== loadingUrl) {
                            loadingImage.src = loadingUrl;
                        } else if (loadingImage.complete && loadingImage.naturalWidth) {
                            spinner.hidden = true;
                            loadingImage.hidden = false;
                            loading.classList.add('has-image');
                        }
                    }
                    var image = new Image();
                    pending = image;
                    image.alt = (card.textContent || '').replace(/^\s+|\s+$/g, '') + ' 首页封面';
                    image.className = 'link-preview-cover';
                    image.decoding = 'async';
                    function failed() {
                        if (token !== sequence) {
                            return;
                        }
                        clearTimeout(timeoutTimer);
                        image.onload = image.onerror = null;
                        pending = null;
                        popup.classList.add('is-error');
                        loading.classList.remove('has-image');
                        status.textContent = '封面加载失败，可点击链接访问网站';
                    }
                    image.onload = function () {
                        if (token !== sequence) {
                            return;
                        }
                        clearTimeout(timeoutTimer);
                        pending = null;
                        image.onload = image.onerror = null;
                        cover = image;
                        popup.appendChild(image);
                        // 下一帧淡入图片，避免加载完成时突然切换。
                        window.requestAnimationFrame(function () {
                            if (token === sequence) {
                                popup.classList.add('is-loaded');
                            }
                        });
                    };
                    image.onerror = failed;
                    timeoutTimer = setTimeout(failed, 12000);
                    image.src = url;
                } else {
                    close();
                    return;
                }
                place(card);
                card.setAttribute('aria-describedby', popup.id);
                popup.setAttribute('aria-hidden', 'false');
                popup.classList.add('is-visible');
            }, 180);
        }

        var cards = document.querySelectorAll('.link-card');
        for (var i = 0; i < cards.length; i++) {
            (function (card) {
                card.addEventListener('mouseenter', function () { show(card); });
                card.addEventListener('mouseleave', close);
                card.addEventListener('focus', function () { show(card); });
                card.addEventListener('blur', close);
                card.addEventListener('click', close);
            })(cards[i]);
        }
        // 捕获分类栏的滚动，一并关闭浮层；不拦截原生滚动。
        window.addEventListener('scroll', close, true);
        window.addEventListener('resize', close);
        document.addEventListener('keydown', function (e) {
            if (e.key === 'Escape' || e.keyCode === 27) {
                close();
            }
        });
    }

    // ============ 下拉菜单（键盘 / 触摸可用） ============
    // 调色板与「更多」菜单原本只靠 :hover 展开，触屏和键盘都无法操作。
    // 这里统一由触发按钮切换 .open，CSS 同时保留 :hover 以维持桌面悬停体验。
    function closeAllDropdowns() {
        var opened = document.querySelectorAll('.dropdown.open, .palette-dropdown.open');
        for (var i = 0; i < opened.length; i++) {
            opened[i].classList.remove('open');
            var trigger = opened[i].querySelector('.icon-btn');
            if (!trigger) {
                continue;
            }
            trigger.setAttribute('aria-expanded', 'false');
            if (opened[i].contains && opened[i].contains(document.activeElement) && document.activeElement !== trigger) {
                try {
                    trigger.focus();
                } catch (e) {
                    // 忽略聚焦失败
                }
            }
        }
    }

    function bindDropdowns() {
        var dropdowns = document.querySelectorAll('.palette-dropdown, .dropdown');
        for (var i = 0; i < dropdowns.length; i++) {
            (function (dropdown) {
                var trigger = dropdown.querySelector('.icon-btn');
                if (!trigger) {
                    return;
                }
                trigger.addEventListener('click', function (e) {
                    // 阻止冒泡，避免刚展开就被 document 的关闭逻辑收起
                    e.stopPropagation();
                    var willOpen = !dropdown.classList.contains('open');
                    closeAllDropdowns();
                    if (willOpen) {
                        dropdown.classList.add('open');
                        trigger.setAttribute('aria-expanded', 'true');
                    }
                });
            })(dropdowns[i]);
        }
        document.addEventListener('click', closeAllDropdowns);
    }

    // ============ 初始化 ============
    function applyDefaultNightMode() {
        var stored = storageGet(NIGHT_KEY);
        if (stored === 'true') {
            document.documentElement.classList.add('night-mode');
            return;
        }
        if (stored === 'false') {
            document.documentElement.classList.remove('night-mode');
            return;
        }
        var mode = bodyData('nightMode') || 'auto';
        var prefersDark = false;
        try {
            prefersDark = !!(window.matchMedia
                && window.matchMedia('(prefers-color-scheme: dark)').matches);
        } catch (e) {
            prefersDark = false;
        }
        var night = mode === 'dark' || (mode === 'auto' && prefersDark);
        if (night) {
            document.documentElement.classList.add('night-mode');
        } else {
            document.documentElement.classList.remove('night-mode');
        }
    }

    function init() {
        setColor(storageGet(COLOR_KEY) || bodyData('defaultColor') || 'purple');
        applyDefaultNightMode();
        updateNightModeIcon();
        updateClock();
        setInterval(updateClock, 1000);
        restoreBackground();
        restoreEngine();
        bindSearch();
        bindCategories();
        bindCategoryScroll();
        bindHoverPreview();
        bindDropdowns();
        bindPageScrollControls();

        var nightBtn = document.getElementById('nightBtn');
        if (nightBtn) {
            nightBtn.addEventListener('click', function () {
                var isNight = document.documentElement.classList.toggle('night-mode');
                storageSet(NIGHT_KEY, isNight ? 'true' : 'false');
                updateNightModeIcon();
            });
        }

        var bgBtn = document.getElementById('bgBtn');
        if (bgBtn) {
            bgBtn.addEventListener('click', cycleBackground);
        }

        document.addEventListener('click', function (e) {
            var target = e.target;
            if (!target.closest('.search-engine-switcher') && !target.closest('.engine-options')) {
                setEngineMenu(false);
            }

            var colorOpt = target.closest('.color-opt');
            if (colorOpt) {
                setColor(colorOpt.getAttribute('data-color'));
                return;
            }

            if (!target.closest('.search-area')) {
                clearSuggestions();
            }
        });

        document.addEventListener('keydown', function (e) {
            var key = e.key || e.keyCode;
            if (key === 'Escape' || key === 27) {
                setEngineMenu(false);
                closeAllDropdowns();
            }
        });

        if (window.innerWidth >= 768 && searchInput) {
            setTimeout(function () {
                try {
                    searchInput.focus();
                } catch (e) {
                    // 忽略聚焦失败
                }
            }, 300);
        }
    }

    function bindPageScrollControls() {
        var controls = document.querySelector('.page-scroll-controls');
        if (!controls) return;
        controls.addEventListener('click', function (event) {
            var button = event.target.closest('[data-scroll-edge]');
            if (!button || !controls.contains(button)) return;
            var root = document.documentElement;
            var height = Math.max(root.scrollHeight, document.body.scrollHeight);
            var top = button.getAttribute('data-scroll-edge') === 'top' ? 0 : height;
            var reduceMotion = window.matchMedia
                && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
            if ('scrollBehavior' in root.style && !reduceMotion) {
                window.scrollTo({ top: top, behavior: 'smooth' });
            } else {
                window.scrollTo(0, top);
            }
        });
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
