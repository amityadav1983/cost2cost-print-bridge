class Cost2CostPrintBridge {
  constructor(options = {}) {
    this.bridgePort = options.bridgePort || 8765;
    this.baseUrl = options.baseUrl || 'http://127.0.0.1';
    this.token = options.token || '';
  }

  buildUrl(path) {
    return this.baseUrl + ':' + this.bridgePort + path;
  }

  async print(receipt) {
    const response = await fetch(this.buildUrl('/print'), {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...(this.token ? { Authorization: 'Bearer ' + this.token } : {})
      },
      body: JSON.stringify(receipt)
    });

    return await response.json();
  }

  async status() {
    const response = await fetch(this.buildUrl('/status'));
    return await response.json();
  }
}

if (typeof window !== 'undefined') {
  window.Cost2CostPrintBridge = Cost2CostPrintBridge;
}

if (typeof module !== 'undefined' && module.exports) {
  module.exports = { Cost2CostPrintBridge };
}
