(function () {
  'use strict';
  // Navigation only. Signature, invitation and access checks remain server-side.
  var query = new URLSearchParams(window.location.search);
  var fragment = new URLSearchParams(window.location.hash.slice(1));
  var signedData = new URLSearchParams(fragment.get('tgWebAppData') || '');
  var candidates = [
    query.get('startapp'),
    query.get('tgWebAppStartParam'),
    fragment.get('tgWebAppStartParam'),
    signedData.get('start_param'),
  ];
  var launch = candidates.find(function (value) {
    return /^(?:ci|cm)_[A-Za-z0-9_-]{43}$/.test(value || '');
  }) || (candidates.includes('client') ? 'client' : null);
  var mainLaunch = !query.has('mode') && Boolean(fragment.get('tgWebAppData'));
  if (launch || mainLaunch) {
    window.__clientEntryRedirect = true;
    document.documentElement.hidden = true;
    var hash = window.location.hash;
    if (launch && launch !== (fragment.get('tgWebAppStartParam') || signedData.get('start_param'))) {
      fragment.set('tgWebAppStartParam', launch);
      hash = '#' + fragment.toString();
    }
    window.location.replace('/app?mode=' + (launch ? 'client' : 'cabinet') + hash);
  }
})();
