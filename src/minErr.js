function minErr(module) {
  var stringify = function (arg) {
    if (typeof arg == 'function') {
      return arg.toString().replace(/ \{[\s\S]*$/, '');
    } else if (typeof arg == 'undefined') {
      return 'undefined';
    } else if (!(typeof arg == 'string')) {
      return JSON.stringify(arg);
    }
    return arg;
  };
  return function () {
    var code = arguments[0],
      prefix = '[' + (module ? module + ':' : '') + code + '] ',
      message,
      i;
    message = prefix + 'MINERR_URL' + (module ? module + 'MINERR_SEPARATOR' : '') + code;
    for (i = 1; i < arguments.length; i++) {
      message = message + (i == 1 ? '?' : '&') + 'p' + (i-1) + '=' +
        encodeURIComponent(stringify(arguments[i]));
    }
    return new Error(message);
  };
}