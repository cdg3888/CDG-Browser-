(() => {
  const MAX_DOMAINS = 300000;
  const IPV4_PATTERN = /^(?:\d{1,3}\.){3}\d{1,3}$/;
  const HOST_LABEL_PATTERN = /^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/;
  const MAPPED_ADDRESSES = new Set([
    "0.0.0.0",
    "127.0.0.1",
    "::",
    "::1",
    "0:0:0:0:0:0:0:0"
  ]);

  function normalizeDomain(value) {
    if (typeof value !== "string") {
      return null;
    }
    const domain = value.trim().toLowerCase().replace(/\.$/, "");
    if (!domain || domain.length > 253 || !domain.includes(".") || IPV4_PATTERN.test(domain)) {
      return null;
    }
    const labels = domain.split(".");
    if (labels.some((label) => label.length > 63 || !HOST_LABEL_PATTERN.test(label))) {
      return null;
    }
    return domain;
  }

  function parseHostsList(text, maxDomains = MAX_DOMAINS) {
    if (typeof text !== "string") {
      throw new TypeError("La lista debe ser texto.");
    }
    const domains = new Set();
    for (const rawLine of text.split(/\r?\n/)) {
      const line = rawLine.split("#", 1)[0].trim();
      if (!line) {
        continue;
      }
      const [address, ...hosts] = line.split(/\s+/);
      if (!MAPPED_ADDRESSES.has(address.toLowerCase())) {
        continue;
      }
      for (const host of hosts) {
        const domain = normalizeDomain(host);
        if (!domain) {
          continue;
        }
        domains.add(domain);
        if (domains.size > maxDomains) {
          throw new RangeError("La lista supera el límite de dominios permitido.");
        }
      }
    }
    return domains;
  }

  function matches(host, domains) {
    let candidate = normalizeDomain(host);
    while (candidate) {
      if (domains.has(candidate)) {
        return true;
      }
      const separator = candidate.indexOf(".");
      candidate = separator < 0 ? null : candidate.slice(separator + 1);
    }
    return false;
  }

  function isFirstParty(firstHost, secondHost) {
    const first = normalizeDomain(firstHost);
    const second = normalizeDomain(secondHost);
    return Boolean(first && second
      && (first === second
        || first.endsWith(`.${second}`)
        || second.endsWith(`.${first}`)));
  }

  globalThis.CDGFilterRules = Object.freeze({
    MAX_DOMAINS,
    isFirstParty,
    matches,
    normalizeDomain,
    parseHostsList
  });
})();
