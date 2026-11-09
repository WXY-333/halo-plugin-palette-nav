/* Palette Nav icon sprite.
 * Original 24x24 line icons authored for this plugin and distributed under the
 * project's MIT license. No third-party icon set is bundled.
 *
 * The public /nav page extracts these <symbol> elements server-side
 * (NavIconSpriteService) and inlines only the ids it actually renders. The console
 * loads this file once so IconPreview's <use href="#pn-*"> references resolve.
 */
(function () {
  var svg = '<svg xmlns="http://www.w3.org/2000/svg">'
    + '<symbol id="pn-common" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="7" height="7" rx="1.5"></rect><rect x="14" y="3" width="7" height="7" rx="1.5"></rect><rect x="3" y="14" width="7" height="7" rx="1.5"></rect><rect x="14" y="14" width="7" height="7" rx="1.5"></rect></symbol>'
    + '<symbol id="pn-design" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="m14 3 7 7-8.5 8.5a3 3 0 0 1-4.2 0l-1.8-1.8a3 3 0 0 1 0-4.2Z"></path><path d="m14 3-3 3"></path><path d="m6 17-3 4 4-1"></path></symbol>'
    + '<symbol id="pn-cloud" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M7 18h9a4 4 0 0 0 .6-8 6 6 0 0 0-11.4 1.8A3.2 3.2 0 0 0 7 18Z"></path></symbol>'
    + '<symbol id="pn-tools" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M14.5 6.5a4 4 0 0 0-5.4 5.4L4 17l3 3 5.1-5.1a4 4 0 0 0 5.4-5.4l-2.4 2.4-2.6-.7-.7-2.6Z"></path></symbol>'
    + '<symbol id="pn-dev" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="m9 8-4 4 4 4"></path><path d="m15 8 4 4-4 4"></path><path d="M13 5l-2 14"></path></symbol>'
    + '<symbol id="pn-game" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="7" width="18" height="10" rx="4"></rect><path d="M8 10v4"></path><path d="M6 12h4"></path><circle cx="16.5" cy="11" r=".9" fill="currentColor" stroke="none"></circle><circle cx="18.5" cy="13" r=".9" fill="currentColor" stroke="none"></circle></symbol>'
    + '<symbol id="pn-video" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="5" width="18" height="14" rx="3"></rect><path d="m10 9 5 3-5 3Z"></path></symbol>'
    + '<symbol id="pn-music" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M9 18V5l10-2v13"></path><circle cx="6" cy="18" r="3"></circle><circle cx="16" cy="16" r="3"></circle></symbol>'
    + '<symbol id="pn-message" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M4 5h16v11H9l-5 4Z"></path></symbol>'
    + '<symbol id="pn-mail" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="5" width="18" height="14" rx="2"></rect><path d="m4 7 8 6 8-6"></path></symbol>'
    + '<symbol id="pn-shopping" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M6 8h12l-1 12H7Z"></path><path d="M9 8V6a3 3 0 0 1 6 0v2"></path></symbol>'
    + '<symbol id="pn-search" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><circle cx="11" cy="11" r="6"></circle><path d="m16 16 5 5"></path></symbol>'
    + '<symbol id="pn-link" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M10 13a5 5 0 0 0 7.1 0l1.4-1.4a5 5 0 0 0-7.1-7.1L10 5.9"></path><path d="M14 11a5 5 0 0 0-7.1 0L5.5 12.4a5 5 0 0 0 7.1 7.1L14 18"></path></symbol>'
    + '<symbol id="pn-code" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M5 4h10l4 4v12H5Z"></path><path d="M9 12l2 2-2 2"></path><path d="M13 16h3"></path></symbol>'
    + '<symbol id="pn-cat-common" viewBox="0 0 24 24"><rect x="1.5" y="1.5" width="21" height="21" rx="6.5" fill="#5B6CFF"></rect><rect x="6.6" y="6.6" width="4.8" height="4.8" rx="1.4" fill="#FFFFFF"></rect><rect x="12.6" y="6.6" width="4.8" height="4.8" rx="1.4" fill="#FFFFFF" fill-opacity=".9"></rect><rect x="6.6" y="12.6" width="4.8" height="4.8" rx="1.4" fill="#FFFFFF" fill-opacity=".9"></rect><rect x="12.6" y="12.6" width="4.8" height="4.8" rx="1.4" fill="#FFFFFF"></rect></symbol>'
    + '<symbol id="pn-cat-design" viewBox="0 0 24 24"><rect x="1.5" y="1.5" width="21" height="21" rx="6.5" fill="#F2618C"></rect><path fill="#FFFFFF" d="M12 5.1a7 7 0 1 0 0 14h1.1a1.9 1.9 0 0 0 0-3.8h-1a1.7 1.7 0 0 1 0-3.4h3.6a3.4 3.4 0 0 0 3.4-3.4A7 7 0 0 0 12 5.1Z"></path><circle cx="8.5" cy="9.3" r="1.15" fill="#F2618C"></circle><circle cx="8.1" cy="13" r="1.15" fill="#F2618C"></circle><circle cx="10.8" cy="15.5" r="1.15" fill="#F2618C"></circle></symbol>'
    + '<symbol id="pn-cat-cloud" viewBox="0 0 24 24"><rect x="1.5" y="1.5" width="21" height="21" rx="6.5" fill="#22B8CF"></rect><path fill="#FFFFFF" d="M7.3 18.3h9.4a3.4 3.4 0 0 0 .6-6.8 5.3 5.3 0 0 0-10.2 1.6 3 3 0 0 0 .2 5.2Z"></path><path d="M12 10.4v4.1M10.3 11.9l1.7-1.7 1.7 1.7" fill="none" stroke="#22B8CF" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"></path></symbol>'
    + '<symbol id="pn-cat-tools" viewBox="0 0 24 24"><rect x="1.5" y="1.5" width="21" height="21" rx="6.5" fill="#F59E0B"></rect><path d="M5.2 8.3h13.6M5.2 12h13.6M5.2 15.7h13.6" fill="none" stroke="#FFFFFF" stroke-width="1.8" stroke-linecap="round"></path><circle cx="9.1" cy="8.3" r="2.4" fill="#FFFFFF"></circle><circle cx="9.1" cy="8.3" r="1.1" fill="#F59E0B"></circle><circle cx="15" cy="12" r="2.4" fill="#FFFFFF"></circle><circle cx="15" cy="12" r="1.1" fill="#F59E0B"></circle><circle cx="10.3" cy="15.7" r="2.4" fill="#FFFFFF"></circle><circle cx="10.3" cy="15.7" r="1.1" fill="#F59E0B"></circle></symbol>'
    + '<symbol id="pn-cat-dev" viewBox="0 0 24 24"><rect x="1.5" y="1.5" width="21" height="21" rx="6.5" fill="#10B981"></rect><path d="M9.4 8.1 5.6 12l3.8 3.9" fill="none" stroke="#FFFFFF" stroke-width="2.1" stroke-linecap="round" stroke-linejoin="round"></path><path d="m14.6 8.1 3.8 3.9-3.8 3.9" fill="none" stroke="#FFFFFF" stroke-width="2.1" stroke-linecap="round" stroke-linejoin="round"></path><path d="m13.2 6.9-2.4 10.2" fill="none" stroke="#FFFFFF" stroke-width="1.6" stroke-linecap="round"></path></symbol>'
    + '<symbol id="pn-cat-games" viewBox="0 0 24 24"><rect x="1.5" y="1.5" width="21" height="21" rx="6.5" fill="#8B5CF6"></rect><rect x="4.4" y="8.2" width="15.2" height="8.2" rx="4.1" fill="#FFFFFF"></rect><path d="M8.8 10.7v3.2M7.2 12.3h3.2" fill="none" stroke="#8B5CF6" stroke-width="1.5" stroke-linecap="round"></path><circle cx="15.4" cy="11.2" r=".95" fill="#8B5CF6"></circle><circle cx="16.9" cy="13.1" r=".95" fill="#8B5CF6"></circle></symbol>'
    + '</svg>';;

  function inject() {
    if (document.getElementById('pn-icon-sprite')) {
      return;
    }
    var holder = document.createElement('div');
    holder.innerHTML = svg;
    var node = holder.getElementsByTagName('svg')[0];
    if (!node) {
      return;
    }
    node.setAttribute('id', 'pn-icon-sprite');
    node.setAttribute('aria-hidden', 'true');
    node.setAttribute('style', 'position:absolute;width:0;height:0;overflow:hidden');
    if (document.body.firstChild) {
      document.body.insertBefore(node, document.body.firstChild);
    } else {
      document.body.appendChild(node);
    }
  }

  if (document.readyState === 'complete' || document.readyState === 'interactive') {
    setTimeout(inject, 0);
  } else {
    document.addEventListener('DOMContentLoaded', inject);
  }
})();