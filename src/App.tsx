import { useRef, useState } from 'react';
import type { FormEvent } from 'react';
import {
  ArrowLeft,
  ArrowRight,
  ArrowUpRight,
  Clock3,
  Globe2,
  History,
  Home,
  LockKeyhole,
  Menu,
  RotateCw,
  Search,
  Settings2,
  ShieldCheck,
  X,
} from 'lucide-react';
import { motion, AnimatePresence } from 'motion/react';

const quickLinks = [
  { name: 'Google', url: 'https://www.google.com', mark: 'G' },
  { name: 'DuckDuckGo', url: 'https://duckduckgo.com', mark: 'D' },
  { name: 'Wikipedia', url: 'https://www.wikipedia.org', mark: 'W' },
  { name: 'GitHub', url: 'https://github.com', mark: '⌘' },
];

export default function App() {
  const [url, setUrl] = useState('https://duckduckgo.com');
  const [inputUrl, setInputUrl] = useState('');
  const [history, setHistory] = useState<string[]>([]);
  const [showHistory, setShowHistory] = useState(false);
  const [showProtection, setShowProtection] = useState(false);
  const [isHome, setIsHome] = useState(true);
  const [isLoading, setIsLoading] = useState(false);
  const iframeRef = useRef<HTMLIFrameElement>(null);

  const handleNavigate = (targetUrl = inputUrl) => {
    let target = targetUrl.trim();
    if (!target) return;

    const looksLikeHost = /^(localhost|[\da-z-]+(?:\.[\da-z-]+)+)(?::\d+)?(?:[/?#][^\s]*)?$/i.test(target);
    if (!/^https?:\/\//i.test(target)) {
      target = looksLikeHost
        ? `https://${target}`
        : `https://duckduckgo.com/?q=${encodeURIComponent(target)}`;
    }

    try {
      const parsed = new URL(target);
      if (!['http:', 'https:'].includes(parsed.protocol)) return;
      target = parsed.href;
    } catch {
      return;
    }

    setUrl(target);
    setInputUrl(target);
    setIsLoading(true);
    setIsHome(false);
    setHistory((previous) => [target, ...previous.filter((item) => item !== target)].slice(0, 30));
  };

  const handleGoHome = () => {
    setIsHome(true);
    setInputUrl('');
    setIsLoading(false);
  };

  const handleRefresh = () => {
    if (iframeRef.current) {
      iframeRef.current.src = url;
      setIsLoading(true);
    }
  };

  const handleSearch = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    handleNavigate();
  };

  return (
    <div className="browser-shell">
      <header className="browser-header">
        <div className="browser-topline">
          <button className="brand-lockup" onClick={handleGoHome} aria-label="Ir al inicio">
            <span className="brand-mark"><ShieldCheck size={18} strokeWidth={2.2} /></span>
            <span className="brand-name">cdg<span>.</span></span>
          </button>
          <div className="tab-pill">
            <Globe2 size={14} />
            <span>{isHome ? 'Nueva pestaña' : new URL(url).hostname.replace(/^www\./, '')}</span>
            {!isHome && <button className="tab-close" onClick={handleGoHome} aria-label="Cerrar pestaña"><X size={13} /></button>}
          </div>
          <button className="icon-button top-menu" onClick={() => setShowProtection(true)} aria-label="Información del navegador">
            <Menu size={18} />
          </button>
        </div>

        <div className="browser-toolbar">
          <div className="toolbar-navigation">
            <button className="icon-button" onClick={handleGoHome} aria-label="Volver al inicio"><ArrowLeft size={18} /></button>
            <button className="icon-button" onClick={handleGoHome} aria-label="Inicio"><Home size={17} /></button>
            <button className="icon-button" onClick={handleRefresh} aria-label="Recargar"><RotateCw size={17} className={isLoading ? 'spin' : ''} /></button>
          </div>

          <form className="address-bar" onSubmit={handleSearch}>
            <LockKeyhole size={15} className="address-lock" />
            <input
              value={inputUrl}
              onChange={(event) => setInputUrl(event.target.value)}
              placeholder="Buscar o escribir una dirección"
              aria-label="Buscar o escribir una dirección"
              spellCheck={false}
            />
            <button className="address-submit" type="submit" aria-label="Buscar"><Search size={17} /></button>
          </form>

          <div className="toolbar-actions">
            <button className={`icon-button ${showHistory ? 'is-selected' : ''}`} onClick={() => setShowHistory((visible) => !visible)} aria-label="Historial">
              <History size={18} />
            </button>
            <button className="icon-button" onClick={() => setShowProtection(true)} aria-label="Ajustes de privacidad">
              <Settings2 size={18} />
            </button>
            {!isHome && (
              <a className="icon-button" href={url} target="_blank" rel="noopener noreferrer" aria-label="Abrir en otra pestaña">
                <ArrowUpRight size={18} />
              </a>
            )}
          </div>
        </div>
      </header>

      <main className="browser-main">
        {isHome ? (
          <motion.section
            className="home-content"
            initial={{ opacity: 0, y: 12 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.35 }}
          >
            <div className="hero">
              <span className="eyebrow"><span className="status-dot" /> Tu espacio para navegar</span>
              <h1>La web, a tu ritmo.</h1>
              <p>Un navegador sencillo, privado y pensado para que lo importante sea el contenido.</p>
            </div>

            <form className="home-search" onSubmit={handleSearch}>
              <Search size={19} />
              <input
                value={inputUrl}
                onChange={(event) => setInputUrl(event.target.value)}
                placeholder="Busca algo o escribe una dirección"
                aria-label="Busca algo o escribe una dirección"
              />
              <button type="submit" aria-label="Buscar"><ArrowRight size={18} /></button>
            </form>

            <section className="quick-section" aria-label="Accesos rápidos">
              <div className="section-heading">
                <h2>Accesos rápidos</h2>
                <span>PARA EMPEZAR</span>
              </div>
              <div className="quick-grid">
                {quickLinks.map((link) => (
                  <button className="quick-card" key={link.name} onClick={() => handleNavigate(link.url)}>
                    <span className={`quick-mark mark-${link.name.toLowerCase()}`}>{link.mark}</span>
                    <span className="quick-copy"><strong>{link.name}</strong><small>{new URL(link.url).hostname}</small></span>
                    <ArrowUpRight size={15} className="quick-arrow" />
                  </button>
                ))}
              </div>
            </section>

            <div className="privacy-note">
              <span className="privacy-icon"><ShieldCheck size={19} /></span>
              <div><strong>Protección integrada</strong><p>El navegador Android usa la protección contra rastreadores de GeckoView.</p></div>
              <button onClick={() => setShowProtection(true)} aria-label="Más información"><ArrowRight size={17} /></button>
            </div>
          </motion.section>
        ) : (
          <div className="page-frame">
            {isLoading && <div className="loading-indicator"><span /><span /><span /></div>}
            <iframe
              ref={iframeRef}
              src={url}
              className="browser-frame"
              onLoad={() => setIsLoading(false)}
              title="Contenido web"
            />
          </div>
        )}

        <AnimatePresence>
          {showHistory && (
            <motion.aside
              className="history-panel"
              initial={{ opacity: 0, x: 18 }}
              animate={{ opacity: 1, x: 0 }}
              exit={{ opacity: 0, x: 18 }}
            >
              <div className="panel-heading">
                <div><span className="eyebrow">TU ACTIVIDAD</span><h2>Historial</h2></div>
                <button className="icon-button" onClick={() => setShowHistory(false)} aria-label="Cerrar historial"><X size={18} /></button>
              </div>
              {history.length === 0 ? (
                <div className="empty-history"><Clock3 size={22} /><p>Aún no hay páginas visitadas.</p></div>
              ) : (
                <div className="history-list">
                  {history.map((item) => (
                    <button key={item} className="history-item" onClick={() => { handleNavigate(item); setShowHistory(false); }}>
                      <Globe2 size={16} /><span>{new URL(item).hostname}<small>{item}</small></span>
                    </button>
                  ))}
                </div>
              )}
            </motion.aside>
          )}
        </AnimatePresence>
      </main>

      <footer className="browser-status">
        <span><span className="status-dot" /> CDG Browser</span>
        <span className="status-caption">Vista web · demostración</span>
      </footer>

      <AnimatePresence>
        {showProtection && (
          <motion.div className="modal-backdrop" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} onClick={() => setShowProtection(false)}>
            <motion.section className="protection-card" initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: 8 }} onClick={(event) => event.stopPropagation()}>
              <button className="icon-button modal-close" onClick={() => setShowProtection(false)} aria-label="Cerrar"><X size={18} /></button>
              <span className="privacy-icon"><ShieldCheck size={21} /></span>
              <span className="eyebrow">PRIVACIDAD</span>
              <h2>Protección integrada</h2>
              <p>La aplicación Android configura la protección de rastreo de GeckoView, incluida la categoría de anuncios. Esta vista web es una demostración y no puede filtrar el contenido cargado dentro de sitios de terceros.</p>
              <button className="modal-confirm" onClick={() => setShowProtection(false)}>Entendido</button>
            </motion.section>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}
