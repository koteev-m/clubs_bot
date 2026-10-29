(function () {
  'use strict';
  // Navigation only. Signature, invitation and access checks remain server-side.
  var fragment = new URLSearchParams(window.location.hash.slice(1));
  var signedData = new URLSearchParams(fragment.get('tgWebAppData') || '');
  var launch =
    fragment.get('tgWebAppStartParam') || signedData.get('start_param') || '';
  var invitation = launch === 'client' || /^ci_[A-Za-z0-9_-]{43}$/.test(launch);
  var mainLaunch = !new URLSearchParams(window.location.search).has('mode') &&
    Boolean(fragment.get('tgWebAppData'));
  if (invitation || mainLaunch) {
    window.__clientEntryRedirect = true;
    document.documentElement.hidden = true;
    // Fragment credentials never become an HTTP query string.
    window.location.replace('/app?mode=' + (invitation ? 'client' : 'cabinet') + window.location.hash);
  }
})();
