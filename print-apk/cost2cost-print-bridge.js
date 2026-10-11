class Cost2CostPrintBridge {
  constructor(options = {}) {
    this.bridgePort = options.bridgePort || 8765;
    this.baseUrl = options.baseUrl || 'http://127.0.0.1';
    this.token = options.token || '';
    this.timeout = options.timeout || 3000;
    this.debug = options.debug !== false;
  }

  buildUrl(path) {
    return this.baseUrl + ':' + this.bridgePort + path;
  }

  log(...args) {
    if (this.debug && typeof console !== 'undefined' && console.log) {
      console.log('[Cost2CostPrintBridge]', ...args);
    }
  }

  errorLog(...args) {
    if (typeof console !== 'undefined' && console.error) {
      console.error('[Cost2CostPrintBridge]', ...args);
    }
  }

  sendXhr(method, path, body = null) {
    return new Promise((resolve, reject) => {
      const url = this.buildUrl(path);
      const xhr = new XMLHttpRequest();

      this.log(`Sending XHR ${method} ${url}`);

      xhr.open(method, url, true);
      xhr.timeout = this.timeout;

      if (this.token) {
        xhr.setRequestHeader('Authorization', 'Bearer ' + this.token);
      }
      if (body) {
        xhr.setRequestHeader('Content-Type', 'application/json');
      }

      xhr.onload = () => {
        this.log(`XHR onload: status=${xhr.status}, responseText=${xhr.responseText}`);
        if (xhr.status >= 200 && xhr.status < 300) {
          try {
            const data = JSON.parse(xhr.responseText);
            resolve({ status: xhr.status, data: data, raw: xhr.responseText });
          } catch (e) {
            reject({
              status: xhr.status,
              error: 'JSON_PARSE_ERROR',
              message: 'Failed to parse JSON response: ' + e.message,
              raw: xhr.responseText
            });
          }
        } else {
          try {
            const errorData = JSON.parse(xhr.responseText);
            reject({ status: xhr.status, data: errorData, raw: xhr.responseText });
          } catch (e) {
            reject({
              status: xhr.status,
              error: 'HTTP_ERROR',
              message: 'HTTP ' + xhr.status + ': ' + (xhr.statusText || 'Error'),
              raw: xhr.responseText
            });
          }
        }
      };

      xhr.onerror = (e) => {
        const isHttps = (typeof window !== 'undefined' && window.location && window.location.protocol === 'https:');
        const errorMsg = isHttps
          ? 'Browser blocked HTTPS-to-HTTP request (Insecure Mixed Content restriction / Network Error). Status: 0'
          : 'Network error or Print Bridge server unreachable. Status: 0';

        this.errorLog(`XHR onerror: ${errorMsg}`, e);
        reject({
          status: 0,
          error: 'NETWORK_ERROR',
          isMixedContent: isHttps,
          message: errorMsg,
          exception: e
        });
      };

      xhr.ontimeout = () => {
        this.errorLog(`XHR ontimeout: Request timed out after ${this.timeout}ms`);
        reject({
          status: 0,
          error: 'TIMEOUT',
          message: `Request timed out after ${this.timeout}ms`
        });
      };

      try {
        xhr.send(body ? (typeof body === 'string' ? body : JSON.stringify(body)) : null);
      } catch (err) {
        this.errorLog('XHR send exception:', err);
        reject({
          status: 0,
          error: 'SEND_EXCEPTION',
          message: 'Failed to send request: ' + err.message,
          exception: err
        });
      }
    });
  }

  async status() {
    try {
      const result = await this.sendXhr('GET', '/status');
      const data = result.data;
      if (data && data.bridgeRunning === true) {
        return { available: true, bridgeRunning: true, ...data };
      }
      return { available: false, bridgeRunning: false, ...data, message: 'Bridge is not running' };
    } catch (err) {
      this.errorLog('Status check failed:', err);
      const isHttps = (typeof window !== 'undefined' && window.location && window.location.protocol === 'https:');
      if (isHttps || err.isMixedContent || err.status === 0) {
        return {
          available: true,
          bridgeRunning: true,
          viaScheme: true,
          message: 'Print Bridge available via native scheme (cost2costprint://)',
          isMixedContent: true
        };
      }
      return {
        available: false,
        bridgeRunning: false,
        error: err.error || 'UNKNOWN_ERROR',
        status: err.status || 0,
        isMixedContent: !!err.isMixedContent,
        message: err.message || 'Print Bridge status check failed',
        raw: err.raw || ''
      };
    }
  }

  async print(receipt) {
    try {
      const result = await this.sendXhr('POST', '/print', receipt);
      return result.data;
    } catch (err) {
      this.errorLog('Print request XHR failed:', err);

      if (err.isMixedContent || err.error === 'NETWORK_ERROR' || err.status === 0) {
        this.log('HTTPS Mixed Content restriction detected. Attempting native app scheme fallback (cost2costprint://)...');
        return this.printViaCustomScheme(receipt);
      }

      return {
        success: false,
        message: err.message || 'Print request failed',
        error: err.error || 'PRINT_FAILED'
      };
    }
  }

  printViaCustomScheme(receipt) {
    try {
      const jsonStr = JSON.stringify(receipt);
      const encoded = encodeURIComponent(jsonStr);
      const schemeUrl = 'cost2costprint://print?data=' + encoded;

      this.log('Launching custom scheme URL:', schemeUrl);
      if (typeof window !== 'undefined') {
        window.location.href = schemeUrl;
        return {
          success: true,
          message: 'Print job handed off to Cost2Cost Print Bridge app via custom scheme',
          viaScheme: true
        };
      }
    } catch (e) {
      this.errorLog('Custom scheme fallback failed:', e);
      return {
        success: false,
        message: 'Failed to trigger custom print scheme: ' + e.message,
        error: 'SCHEME_FAILED'
      };
    }
  }
}

if (typeof window !== 'undefined') {
  window.Cost2CostPrintBridge = Cost2CostPrintBridge;
}

if (typeof module !== 'undefined' && module.exports) {
  module.exports = { Cost2CostPrintBridge };
}
