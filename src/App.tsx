/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 */

import React, { useState, useEffect, useRef } from 'react';
import { 
  Shield, 
  ShieldCheck, 
  ShieldAlert, 
  Search, 
  ArrowLeft, 
  ArrowRight, 
  RotateCcw, 
  Lock, 
  Settings, 
  History,
  ExternalLink,
  Info,
  X,
  Menu,
  Globe
} from 'lucide-react';
import { motion, AnimatePresence } from 'motion/react';

export default function App() {
  const [url, setUrl] = useState('https://www.google.com');
  const [inputUrl, setInputUrl] = useState('https://www.google.com');
  const [history, setHistory] = useState<string[]>([]);
  const [showHistory, setShowHistory] = useState(false);
  const [isHome, setIsHome] = useState(true);
  const [proxyUrl, setProxyUrl] = useState('');
  const [blockedCount, setBlockedCount] = useState(0);
  const [totalBlocked, setTotalBlocked] = useState(0);
  const [isLoading, setIsLoading] = useState(false);
  const [showPrivacyPanel, setShowPrivacyPanel] = useState(false);
  const iframeRef = useRef<HTMLIFrameElement>(null);

  useEffect(() => {
    handleNavigate('https://www.google.com');
  }, []);

  useEffect(() => {
    const handleMessage = (event: MessageEvent) => {
      if (event.data.type === 'STATS') {
        setBlockedCount(event.data.blocked);
        setTotalBlocked(prev => prev + event.data.blocked);
      }
    };
    window.addEventListener('message', handleMessage);
    return () => window.removeEventListener('message', handleMessage);
  }, []);

  const handleNavigate = (targetUrl?: string) => {
    let target = (targetUrl || inputUrl).trim();
    if (!target) return;
    
    if (!target.startsWith('http')) {
      if (target.includes('.') && !target.includes(' ')) {
        target = 'https://' + target;
      } else {
        target = `https://www.google.com/search?q=${encodeURIComponent(target)}`;
      }
    }
    
    setUrl(target);
    setInputUrl(target);
    setProxyUrl(`/api/proxy?url=${encodeURIComponent(target)}`);
    setIsLoading(true);
    setIsHome(false);
    setHistory(prev => {
      const newHistory = [target, ...prev.filter(h => h !== target)];
      return newHistory.slice(0, 20);
    });
  };

  const handleGoHome = () => {
    setIsHome(true);
    setUrl('Home');
    setInputUrl('');
    setProxyUrl('');
    setBlockedCount(0);
  };

  const handleRefresh = () => {
    if (iframeRef.current && proxyUrl) {
      iframeRef.current.src = proxyUrl;
      setIsLoading(true);
    }
  };

  const handleIframeLoad = () => {
    setIsLoading(false);
  };

  const quickLinks = [
    { name: 'Google', url: 'https://www.google.com' },
    { name: 'DuckDuckGo', url: 'https://duckduckgo.com' },
    { name: 'Wikipedia', url: 'https://www.wikipedia.org' },
    { name: 'GitHub', url: 'https://github.com' },
  ];

  return (
    <div className="flex flex-col h-screen bg-[#E4E3E0] text-[#141414] font-sans overflow-hidden">
      {/* Browser Header */}
      <header className="flex flex-col border-b border-[#141414] bg-white z-20">
        {/* Top Bar: Tabs & Controls */}
        <div className="flex items-center px-4 py-2 gap-4 border-b border-[#141414]/10">
          <div className="flex items-center gap-2">
            <div className="w-3 h-3 rounded-full bg-red-400" />
            <div className="w-3 h-3 rounded-full bg-yellow-400" />
            <div className="w-3 h-3 rounded-full bg-green-400" />
          </div>
          
          <div className="flex-1 flex items-center gap-2">
            <div 
              onClick={handleGoHome}
              className={`px-4 py-1.5 border border-[#141414] border-b-0 rounded-t-lg text-xs font-medium flex items-center gap-2 max-w-[200px] truncate cursor-pointer transition-colors ${
                isHome ? 'bg-white' : 'bg-[#E4E3E0] hover:bg-white/50'
              }`}
            >
              <Globe size={12} />
              {isHome ? 'CDG Home' : url.replace('https://', '').replace('www.', '')}
              {!isHome && <X size={10} className="ml-2 cursor-pointer hover:text-red-500" onClick={(e) => { e.stopPropagation(); handleGoHome(); }} />}
            </div>
          </div>

          <div className="flex items-center gap-4">
            <button 
              onClick={() => setShowPrivacyPanel(!showPrivacyPanel)}
              className={`flex items-center gap-2 px-3 py-1 rounded-full text-xs font-mono transition-colors ${
                blockedCount > 0 ? 'bg-green-100 text-green-800 border border-green-200' : 'bg-gray-100 text-gray-600 border border-gray-200'
              }`}
            >
              <ShieldCheck size={14} />
              <span>SHIELD ACTIVE: {blockedCount}</span>
            </button>
            <Menu size={18} className="cursor-pointer opacity-60 hover:opacity-100" />
          </div>
        </div>

        {/* Navigation Bar */}
        <div className="flex items-center px-4 py-2 gap-4">
          <div className="flex items-center gap-2">
            <button onClick={handleGoHome} className="p-1.5 hover:bg-gray-100 rounded-md transition-colors">
              <ArrowLeft size={18} className={history.length > 1 ? 'opacity-100' : 'opacity-30'} />
            </button>
            <button className="p-1.5 hover:bg-gray-100 rounded-md transition-colors opacity-30 cursor-not-allowed">
              <ArrowRight size={18} />
            </button>
            <button onClick={handleRefresh} className="p-1.5 hover:bg-gray-100 rounded-md transition-colors">
              <RotateCcw size={18} className={isLoading ? 'animate-spin' : ''} />
            </button>
          </div>

          <div className="flex-1 relative group">
            <div className="absolute left-3 top-1/2 -translate-y-1/2 text-gray-400">
              <Lock size={14} />
            </div>
            <input 
              type="text" 
              value={inputUrl}
              onChange={(e) => setInputUrl(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && handleNavigate()}
              className="w-full bg-gray-100 border border-transparent focus:border-[#141414] focus:bg-white rounded-lg py-2 pl-10 pr-4 text-sm transition-all outline-none"
              placeholder="Enter URL or search..."
            />
            <button 
              onClick={() => handleNavigate()}
              className="absolute right-2 top-1/2 -translate-y-1/2 p-1 hover:bg-gray-200 rounded transition-colors"
            >
              <Search size={16} />
            </button>
          </div>

          <div className="flex items-center gap-2">
            <button onClick={() => setShowHistory(!showHistory)} className={`p-1.5 rounded-md transition-colors ${showHistory ? 'bg-gray-200' : 'hover:bg-gray-100'}`}>
              <History size={18} />
            </button>
            <button className="p-1.5 hover:bg-gray-100 rounded-md transition-colors">
              <Settings size={18} />
            </button>
          </div>
        </div>
      </header>

      {/* Main Content Area */}
      <main className="flex-1 relative flex">
        {/* Iframe Container or Home Page */}
        <div className="flex-1 bg-white relative">
          {isHome ? (
            <div className="absolute inset-0 flex flex-col items-center justify-center bg-[#E4E3E0] p-8">
              <motion.div 
                initial={{ opacity: 0, y: 20 }}
                animate={{ opacity: 1, y: 0 }}
                className="max-w-2xl w-full text-center space-y-12"
              >
                <div className="space-y-4">
                  <div className="flex justify-center">
                    <div className="p-6 bg-white border-2 border-[#141414] rounded-3xl shadow-[8px_8px_0px_0px_rgba(20,20,20,1)]">
                      <ShieldCheck size={64} className="text-[#141414]" />
                    </div>
                  </div>
                  <h1 className="text-6xl font-serif italic font-bold tracking-tight">CDG Browser</h1>
                  <p className="text-sm font-mono uppercase tracking-[0.2em] opacity-60">Secure • Private • Fast</p>
                </div>

                <div className="relative max-w-lg mx-auto">
                  <input 
                    type="text" 
                    value={inputUrl}
                    onChange={(e) => setInputUrl(e.target.value)}
                    onKeyDown={(e) => e.key === 'Enter' && handleNavigate()}
                    className="w-full bg-white border-2 border-[#141414] rounded-2xl py-4 px-6 text-lg shadow-[4px_4px_0px_0px_rgba(20,20,20,1)] outline-none focus:translate-x-[2px] focus:translate-y-[2px] focus:shadow-[2px_2px_0px_0px_rgba(20,20,20,1)] transition-all"
                    placeholder="Search the web privately..."
                  />
                  <button 
                    onClick={() => handleNavigate()}
                    className="absolute right-3 top-1/2 -translate-y-1/2 bg-[#141414] text-white p-2 rounded-xl hover:scale-105 transition-transform"
                  >
                    <ArrowRight size={24} />
                  </button>
                </div>

                <div className="flex flex-wrap justify-center gap-4">
                  {quickLinks.map((link, i) => (
                    <button 
                      key={i}
                      onClick={() => handleNavigate(link.url)}
                      className="px-6 py-2 bg-white border border-[#141414]/10 rounded-full text-sm hover:border-[#141414] hover:bg-[#141414] hover:text-white transition-all"
                    >
                      {link.name}
                    </button>
                  ))}
                </div>

                <div className="pt-12 grid grid-cols-3 gap-8 text-left">
                  <div className="space-y-2">
                    <div className="flex items-center gap-2 font-bold text-xs uppercase tracking-wider">
                      <Shield size={14} />
                      Ad Block
                    </div>
                    <p className="text-[11px] opacity-60 leading-relaxed">Advanced filtering removes intrusive ads and banners before they load.</p>
                  </div>
                  <div className="space-y-2">
                    <div className="flex items-center gap-2 font-bold text-xs uppercase tracking-wider">
                      <Lock size={14} />
                      Privacy
                    </div>
                    <p className="text-[11px] opacity-60 leading-relaxed">Tracker and analytics blocking prevents companies from following you.</p>
                  </div>
                  <div className="space-y-2">
                    <div className="flex items-center gap-2 font-bold text-xs uppercase tracking-wider">
                      <RotateCcw size={14} />
                      Proxy
                    </div>
                    <p className="text-[11px] opacity-60 leading-relaxed">All traffic is routed through our secure proxy to hide your identity.</p>
                  </div>
                </div>
              </motion.div>
            </div>
          ) : (
            <>
              {isLoading && (
                <div className="absolute inset-0 z-10 bg-white/80 backdrop-blur-sm flex items-center justify-center">
                  <div className="flex flex-col items-center gap-4">
                    <div className="w-12 h-12 border-4 border-[#141414] border-t-transparent rounded-full animate-spin" />
                    <p className="font-mono text-xs uppercase tracking-widest animate-pulse">Filtering Content...</p>
                  </div>
                </div>
              )}
              <iframe 
                ref={iframeRef}
                src={proxyUrl}
                className="w-full h-full border-none"
                onLoad={handleIframeLoad}
                title="Browser Content"
              />
            </>
          )}
        </div>

        {/* History Panel */}
        <AnimatePresence>
          {showHistory && (
            <motion.aside 
              initial={{ x: '-100%' }}
              animate={{ x: 0 }}
              exit={{ x: '-100%' }}
              className="absolute left-0 top-0 bottom-0 w-72 bg-white border-r border-[#141414] z-30 shadow-2xl p-6 overflow-y-auto"
            >
              <div className="flex items-center justify-between mb-8">
                <h2 className="font-serif italic text-xl">History</h2>
                <button onClick={() => setShowHistory(false)} className="p-1 hover:bg-gray-100 rounded">
                  <X size={20} />
                </button>
              </div>
              <div className="space-y-2">
                {history.length === 0 ? (
                  <p className="text-xs opacity-40 italic">No history yet.</p>
                ) : (
                  history.map((item, i) => (
                    <button 
                      key={i}
                      onClick={() => { handleNavigate(item); setShowHistory(false); }}
                      className="w-full text-left p-3 hover:bg-gray-50 rounded-lg border border-transparent hover:border-[#141414]/10 transition-all group"
                    >
                      <p className="text-xs font-medium truncate mb-1">{item}</p>
                      <div className="flex items-center gap-2 text-[10px] opacity-40 group-hover:opacity-100 transition-opacity">
                        <Globe size={10} />
                        <span>{new URL(item).hostname}</span>
                      </div>
                    </button>
                  ))
                )}
              </div>
            </motion.aside>
          )}
        </AnimatePresence>

        {/* Privacy Panel (Sidebar) */}
        <AnimatePresence>
          {showPrivacyPanel && (
            <motion.aside 
              initial={{ x: '100%' }}
              animate={{ x: 0 }}
              exit={{ x: '100%' }}
              transition={{ type: 'spring', damping: 25, stiffness: 200 }}
              className="absolute right-0 top-0 bottom-0 w-80 bg-white border-l border-[#141414] z-30 shadow-2xl p-6 overflow-y-auto"
            >
              <div className="flex items-center justify-between mb-8">
                <h2 className="font-serif italic text-xl">CDG Shield</h2>
                <button onClick={() => setShowPrivacyPanel(false)} className="p-1 hover:bg-gray-100 rounded">
                  <X size={20} />
                </button>
              </div>

              <div className="space-y-6">
                <div className="p-4 bg-green-50 border border-green-200 rounded-xl">
                  <div className="flex items-center gap-3 mb-2 text-green-800">
                    <ShieldCheck size={20} />
                    <span className="font-bold">Protection Active</span>
                  </div>
                  <p className="text-xs text-green-700 leading-relaxed">
                    CDG Browser is currently filtering this page for malicious scripts, trackers, and intrusive advertisements.
                  </p>
                </div>

                <div className="grid grid-cols-2 gap-4">
                  <div className="p-4 bg-[#E4E3E0] rounded-xl">
                    <p className="text-[10px] uppercase tracking-wider opacity-50 mb-1">Blocked on Page</p>
                    <p className="text-2xl font-mono font-bold">{blockedCount}</p>
                  </div>
                  <div className="p-4 bg-[#E4E3E0] rounded-xl">
                    <p className="text-[10px] uppercase tracking-wider opacity-50 mb-1">Total Blocked</p>
                    <p className="text-2xl font-mono font-bold">{totalBlocked}</p>
                  </div>
                </div>

                <div className="space-y-4">
                  <h3 className="text-xs font-bold uppercase tracking-widest border-b border-[#141414]/10 pb-2">Active Filters</h3>
                  <ul className="space-y-3">
                    {[
                      { label: 'Ad Blocking', status: 'Active', icon: <Shield size={14} /> },
                      { label: 'Tracker Blocking', status: 'Active', icon: <Shield size={14} /> },
                      { label: 'Popup Blocker', status: 'Active', icon: <Shield size={14} /> },
                      { label: 'Analytics Blocking', status: 'Active', icon: <Shield size={14} /> },
                      { label: 'Banner Removal', status: 'Active', icon: <Shield size={14} /> },
                    ].map((item, i) => (
                      <li key={i} className="flex items-center justify-between text-sm">
                        <div className="flex items-center gap-2 opacity-70">
                          {item.icon}
                          <span>{item.label}</span>
                        </div>
                        <span className="text-[10px] font-mono bg-green-100 text-green-800 px-1.5 py-0.5 rounded uppercase font-bold">
                          {item.status}
                        </span>
                      </li>
                    ))}
                  </ul>
                </div>

                <div className="pt-8">
                  <div className="p-4 border border-[#141414]/10 rounded-xl bg-gray-50">
                    <div className="flex items-center gap-2 mb-2">
                      <Info size={14} className="text-blue-500" />
                      <span className="text-xs font-bold">About CDG Browser</span>
                    </div>
                    <p className="text-[11px] leading-relaxed opacity-70">
                      CDG Browser uses a server-side proxy to sanitize web content before it reaches your device. This ensures that tracking pixels and malicious scripts are neutralized at the source.
                    </p>
                  </div>
                </div>
              </div>
            </motion.aside>
          )}
        </AnimatePresence>
      </main>

      {/* Footer / Status Bar */}
      <footer className="bg-white border-t border-[#141414] px-4 py-1.5 flex items-center justify-between text-[10px] font-mono uppercase tracking-wider opacity-60">
        <div className="flex items-center gap-4">
          <div className="flex items-center gap-1">
            <div className="w-1.5 h-1.5 rounded-full bg-green-500" />
            <span>Proxy Connected</span>
          </div>
          <span>TLS 1.3 Active</span>
        </div>
        <div className="flex items-center gap-4">
          <div className="flex items-center gap-2">
            <span>Quick Access:</span>
            {quickLinks.map((link, i) => (
              <button 
                key={i}
                onClick={() => handleNavigate(link.url)}
                className="hover:text-[#141414] hover:underline"
              >
                {link.name}
              </button>
            ))}
          </div>
          <span>v1.0.4-STABLE</span>
        </div>
      </footer>
    </div>
  );
}
