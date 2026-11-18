import React, { useState, useEffect } from 'react';
import { Lock, RotateCcw, Power, ShieldAlert, CheckCircle2, XCircle, Clock, Loader2, Sparkles, ShieldCheck } from 'lucide-react';
import { metricsService } from '../services/metricsService';

const RemotePowerManagement = ({ computer, onStatusUpdate }) => {
  const [modalType, setModalType] = useState(null); // 'LOCK' | 'RESTART' | 'SHUTDOWN' | null
  const [loading, setLoading] = useState(false);
  const [feedback, setFeedback] = useState(null); // { type: 'success' | 'error', message: '', statusText: '' }
  const [audits, setAudits] = useState([]);
  const [lastCommandStatus, setLastCommandStatus] = useState(null);

  // Accept ONLINE, CRITICAL, or WARNING as active online computer states
  const isOnline = computer && (
    computer.status === 'ONLINE' || 
    computer.status === 'CRITICAL' || 
    computer.status === 'WARNING'
  );

  useEffect(() => {
    if (computer?.id) {
      fetchAudits();
    }
  }, [computer?.id]);

  const fetchAudits = async () => {
    try {
      const res = await metricsService.getPowerAudits(computer.id);
      if (res?.success && Array.isArray(res.data)) {
        setAudits(res.data);
      }
    } catch (e) {
      console.error('Failed to load power audits', e);
    }
  };

  const handleConfirmAction = async () => {
    if (!modalType || !isOnline) return;
    const action = modalType;
    setModalType(null);
    setLoading(true);
    setFeedback(null);

    const clickIsoTime = new Date().toISOString();
    console.log(`[PERF LOG] [FRONTEND] User confirmed ${action} action at ${clickIsoTime}`);
    const startMs = performance.now();

    try {
      let res;
      if (action === 'LOCK') {
        res = await metricsService.lockComputer(computer.id);
      } else if (action === 'RESTART') {
        res = await metricsService.restartComputer(computer.id);
      } else if (action === 'SHUTDOWN') {
        res = await metricsService.shutdownComputer(computer.id);
      }

      const endMs = performance.now();
      const latencyMs = Math.round(endMs - startMs);
      console.log(`[PERF LOG] [FRONTEND] Backend acknowledged ${action} command in ${latencyMs}ms at ${new Date().toISOString()}`);

      const isSuccess = res && (res.success === true || res.id || res.status);

      if (isSuccess) {
        const headlineText = action === 'LOCK'
          ? "Lock Command Delivered"
          : action === 'RESTART'
          ? "Restart Command Delivered"
          : "Shutdown Command Delivered";

        const detailText = action === 'LOCK'
          ? `${computer.hostname} workstation screen is locking immediately.`
          : action === 'RESTART'
          ? `${computer.hostname} is restarting now...`
          : `${computer.hostname} is powering down safely...`;

        setFeedback({ 
          type: 'success', 
          message: headlineText,
          statusText: detailText,
          latency: latencyMs
        });
        
        setLastCommandStatus({ type: action, status: res.status || res.data?.status || 'ACKNOWLEDGED' });
        fetchAudits();
        if (onStatusUpdate) onStatusUpdate();
      } else {
        setFeedback({ 
          type: 'error', 
          message: `Unable to send ${action.toLowerCase()} command.`, 
          statusText: res?.message || 'Check agent connectivity and try again.' 
        });
      }
    } catch (err) {
      setFeedback({ 
        type: 'error', 
        message: `Unable to send ${action.toLowerCase()} command.`, 
        statusText: err.response?.data?.message || err.message 
      });
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="bg-white rounded-2xl border-2 border-slate-200 shadow-md p-6 space-y-6 relative overflow-hidden">
      {/* Top Colorful Accent Line */}
      <div className="absolute top-0 left-0 right-0 h-1.5 bg-gradient-to-r from-amber-500 via-blue-600 to-red-600" />

      {/* Header Bar */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 border-b border-slate-200 pb-5">
        <div className="flex items-center gap-3.5">
          <div className="w-12 h-12 rounded-xl bg-blue-50 border border-blue-200 flex items-center justify-center text-blue-600 shadow-xs">
            <Power className="w-6 h-6 text-blue-600" />
          </div>
          <div>
            <div className="flex items-center gap-2">
              <h3 className="text-lg font-black text-slate-900 tracking-wide">
                REMOTE POWER MANAGEMENT
              </h3>
              <span className="bg-blue-100 text-blue-800 text-[10px] font-extrabold uppercase px-2 py-0.5 rounded-full border border-blue-200">
                Direct Control
              </span>
            </div>
            <p className="text-xs text-slate-600 font-medium mt-0.5">
              Execute immediate hardware power and screen commands on <strong className="text-slate-900 font-bold bg-slate-100 px-2 py-0.5 rounded border border-slate-200">{computer?.hostname}</strong>
            </p>
          </div>
        </div>

        <div className="flex items-center gap-2">
          <div className={`inline-flex items-center px-3.5 py-1.5 rounded-full text-xs font-extrabold border shadow-xs ${
            isOnline 
              ? 'bg-emerald-50 text-emerald-800 border-emerald-300' 
              : 'bg-red-50 text-red-800 border-red-300'
          }`}>
            <span className={`w-2.5 h-2.5 rounded-full mr-2 ${isOnline ? 'bg-emerald-500 animate-pulse' : 'bg-red-500'}`} />
            {isOnline ? `ONLINE (${computer?.status || 'CONNECTED'})` : 'OFFLINE (DISCONNECTED)'}
          </div>
        </div>
      </div>

      {/* Action Buttons Section */}
      {!isOnline ? (
        <div className="p-5 rounded-xl bg-amber-50/80 border-2 border-amber-200 text-amber-900 text-xs flex items-center gap-4">
          <div className="p-2.5 bg-amber-100 rounded-lg text-amber-700">
            <ShieldAlert className="w-6 h-6 flex-shrink-0" />
          </div>
          <div>
            <strong className="text-sm font-bold block text-amber-950 mb-0.5">Workstation {computer?.hostname} is currently Offline</strong>
            Remote power actions are disabled because the workstation is not connected to the monitoring agent. Once the machine boots and connects, controls will become active immediately.
          </div>
        </div>
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
          {/* Lock Button */}
          <button
            onClick={() => setModalType('LOCK')}
            disabled={loading}
            className="p-4 rounded-xl bg-gradient-to-br from-amber-500 to-amber-600 hover:from-amber-600 hover:to-amber-700 active:scale-[0.99] text-white font-bold transition-all shadow-md hover:shadow-lg flex items-center gap-3.5 cursor-pointer disabled:opacity-50 disabled:cursor-not-allowed group border border-amber-400"
          >
            <div className="p-2.5 bg-white/20 rounded-lg group-hover:scale-110 transition-transform">
              <Lock className="w-5 h-5 text-white" />
            </div>
            <div className="text-left">
              <span className="block text-sm font-extrabold text-white tracking-wide">Lock Screen</span>
              <span className="block text-[11px] text-amber-100 font-medium">Instantly lock active Windows session</span>
            </div>
          </button>

          {/* Restart Button */}
          <button
            onClick={() => setModalType('RESTART')}
            disabled={loading}
            className="p-4 rounded-xl bg-gradient-to-br from-blue-600 to-indigo-700 hover:from-blue-700 hover:to-indigo-800 active:scale-[0.99] text-white font-bold transition-all shadow-md hover:shadow-lg flex items-center gap-3.5 cursor-pointer disabled:opacity-50 disabled:cursor-not-allowed group border border-blue-400"
          >
            <div className="p-2.5 bg-white/20 rounded-lg group-hover:rotate-180 transition-transform duration-500">
              <RotateCcw className="w-5 h-5 text-white" />
            </div>
            <div className="text-left">
              <span className="block text-sm font-extrabold text-white tracking-wide">Restart Computer</span>
              <span className="block text-[11px] text-blue-100 font-medium">Reboot OS and reload services</span>
            </div>
          </button>

          {/* Shutdown Button */}
          <button
            onClick={() => setModalType('SHUTDOWN')}
            disabled={loading}
            className="p-4 rounded-xl bg-gradient-to-br from-red-600 to-rose-700 hover:from-red-700 hover:to-rose-800 active:scale-[0.99] text-white font-bold transition-all shadow-md hover:shadow-lg flex items-center gap-3.5 cursor-pointer disabled:opacity-50 disabled:cursor-not-allowed group border border-red-400"
          >
            <div className="p-2.5 bg-white/20 rounded-lg group-hover:scale-110 transition-transform">
              <Power className="w-5 h-5 text-white" />
            </div>
            <div className="text-left">
              <span className="block text-sm font-extrabold text-white tracking-wide">Shut Down Computer</span>
              <span className="block text-[11px] text-red-100 font-medium">Safely power off machine</span>
            </div>
          </button>
        </div>
      )}

      {/* Loading Indicator */}
      {loading && (
        <div className="p-3.5 rounded-xl bg-blue-50 border border-blue-200 text-blue-900 text-xs flex items-center justify-center gap-2 animate-pulse font-bold">
          <Loader2 className="w-4 h-4 animate-spin text-blue-600" />
          Dispatching remote power command to workstation agent...
        </div>
      )}

      {/* Feedback Toast Banner */}
      {feedback && (
        <div className={`p-4 rounded-xl border text-xs space-y-1 shadow-sm ${
          feedback.type === 'success' 
            ? 'bg-emerald-50 border-emerald-300 text-emerald-950' 
            : 'bg-red-50 border-red-300 text-red-950'
        }`}>
          <div className="flex items-center justify-between">
            <div className="flex items-center space-x-2">
              {feedback.type === 'success' ? (
                <CheckCircle2 className="w-5 h-5 text-emerald-600 flex-shrink-0" />
              ) : (
                <XCircle className="w-5 h-5 text-red-600 flex-shrink-0" />
              )}
              <strong className="text-sm font-extrabold">{feedback.message}</strong>
            </div>
            <button 
              onClick={() => setFeedback(null)} 
              className="text-slate-500 hover:text-slate-800 font-bold text-xs p-1 cursor-pointer"
            >
              ✕
            </button>
          </div>
          {feedback.statusText && (
            <p className="text-xs text-slate-700 font-medium pl-7">
              {feedback.statusText}
            </p>
          )}
          {feedback.latency && (
            <p className="text-[11px] text-emerald-700 font-mono font-bold pl-7 pt-0.5">
              ⚡ Delivered to agent in {feedback.latency}ms
            </p>
          )}
        </div>
      )}

      {/* Last Command Status Display */}
      {lastCommandStatus && (
        <div className="p-3 rounded-xl bg-slate-50 border border-slate-200 flex items-center justify-between text-xs">
          <span className="text-slate-700 font-medium">Last Sent Action: <strong className="text-slate-900 font-bold">{lastCommandStatus.type}</strong></span>
          <span className="px-3 py-1 rounded-full font-mono text-xs font-extrabold bg-blue-100 text-blue-800 border border-blue-200">
            {lastCommandStatus.status}
          </span>
        </div>
      )}

      {/* Recent Audit Log */}
      {audits.length > 0 && (
        <div className="space-y-3 pt-2 border-t border-slate-200">
          <div className="flex items-center justify-between">
            <h4 className="text-xs font-bold text-slate-800 uppercase tracking-wider flex items-center gap-1.5">
              <Clock className="w-4 h-4 text-slate-600" /> Recent Power Action Audit History
            </h4>
            <span className="text-[11px] text-slate-500 font-medium">Real-time Command Log</span>
          </div>
          <div className="max-h-40 overflow-y-auto space-y-2 pr-1">
            {audits.slice(0, 5).map((audit) => (
              <div key={audit.id} className="p-3 rounded-lg bg-slate-50 border border-slate-200 flex items-center justify-between text-xs hover:bg-slate-100/70 transition-colors">
                <div className="flex items-center space-x-2.5">
                  <span className="font-bold text-slate-900">{audit.userName || 'Administrator'}</span>
                  <span className="text-slate-400">•</span>
                  <span className={`font-mono font-extrabold px-2 py-0.5 rounded text-[11px] ${
                    audit.action === 'SHUTDOWN' 
                      ? 'bg-red-100 text-red-800 border border-red-200' 
                      : audit.action === 'RESTART' 
                      ? 'bg-amber-100 text-amber-800 border border-amber-200' 
                      : 'bg-blue-100 text-blue-800 border border-blue-200'
                  }`}>
                    {audit.action}
                  </span>
                </div>
                <div className="flex items-center space-x-3">
                  <span className="text-slate-600 font-mono text-xs font-semibold">
                    {new Date(audit.timestamp).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' })}
                  </span>
                  <span className={`px-2.5 py-0.5 rounded-full text-[10px] font-extrabold ${
                    audit.status === 'SUCCESS' || audit.status === 'ACKNOWLEDGED' || audit.status === 'SENT' || audit.status === 'QUEUED'
                      ? 'bg-emerald-100 text-emerald-800 border border-emerald-300' 
                      : 'bg-red-100 text-red-800 border border-red-300'
                  }`}>
                    {audit.status}
                  </span>
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* CONFIRMATION MODALS (Light Theme, Clean High-Contrast) */}

      {/* 🔒 Lock Confirmation Modal */}
      {modalType === 'LOCK' && (
        <div className="fixed inset-0 z-50 bg-black/60 backdrop-blur-xs flex items-center justify-center p-4">
          <div className="bg-white border-2 border-slate-200 rounded-2xl max-w-md w-full p-6 space-y-4 shadow-2xl animate-in fade-in zoom-in-95 duration-200">
            <div className="flex items-center space-x-3 text-amber-600">
              <div className="p-2.5 bg-amber-100 rounded-xl">
                <Lock className="w-6 h-6 text-amber-600" />
              </div>
              <div>
                <h3 className="text-lg font-bold text-slate-900">Lock Workstation?</h3>
                <p className="text-xs text-slate-500 font-medium">Target: {computer?.hostname}</p>
              </div>
            </div>
            <p className="text-xs text-slate-700 leading-relaxed font-medium bg-slate-50 p-3 rounded-xl border border-slate-200">
              You are about to lock the screen for <strong className="text-slate-900 font-bold">{computer?.hostname}</strong>. The active Windows user session will be locked immediately and will require user credentials to unlock.
            </p>
            <div className="flex items-center justify-end space-x-3 pt-3 border-t border-slate-200">
              <button
                onClick={() => setModalType(null)}
                className="px-4 py-2.5 rounded-xl text-xs font-bold text-slate-700 hover:bg-slate-100 border border-slate-300 transition-colors cursor-pointer"
              >
                Cancel
              </button>
              <button
                onClick={handleConfirmAction}
                className="px-5 py-2.5 rounded-xl text-xs font-extrabold bg-amber-500 hover:bg-amber-600 text-white shadow-md hover:shadow-lg transition-all cursor-pointer flex items-center gap-1.5"
              >
                <Lock className="w-4 h-4" />
                <span>Confirm Lock Workstation</span>
              </button>
            </div>
          </div>
        </div>
      )}

      {/* 🔄 Restart Confirmation Modal */}
      {modalType === 'RESTART' && (
        <div className="fixed inset-0 z-50 bg-black/60 backdrop-blur-xs flex items-center justify-center p-4">
          <div className="bg-white border-2 border-blue-200 rounded-2xl max-w-md w-full p-6 space-y-4 shadow-2xl animate-in fade-in zoom-in-95 duration-200">
            <div className="flex items-center space-x-3 text-blue-600">
              <div className="p-2.5 bg-blue-100 rounded-xl">
                <RotateCcw className="w-6 h-6 text-blue-600" />
              </div>
              <div>
                <h3 className="text-lg font-bold text-slate-900">Restart Workstation?</h3>
                <p className="text-xs text-slate-500 font-medium">Target: {computer?.hostname}</p>
              </div>
            </div>
            <p className="text-xs text-slate-700 leading-relaxed font-medium bg-amber-50 p-3 rounded-xl border border-amber-200">
              ⚠️ <strong className="text-amber-900 font-bold">{computer?.hostname}</strong> will be rebooted immediately. Any unsaved user applications or work will be terminated by the operating system.
            </p>
            <div className="flex items-center justify-end space-x-3 pt-3 border-t border-slate-200">
              <button
                onClick={() => setModalType(null)}
                className="px-4 py-2.5 rounded-xl text-xs font-bold text-slate-700 hover:bg-slate-100 border border-slate-300 transition-colors cursor-pointer"
              >
                Cancel
              </button>
              <button
                onClick={handleConfirmAction}
                className="px-5 py-2.5 rounded-xl text-xs font-extrabold bg-blue-600 hover:bg-blue-700 text-white shadow-md hover:shadow-lg transition-all cursor-pointer flex items-center gap-1.5"
              >
                <RotateCcw className="w-4 h-4" />
                <span>Confirm Restart Workstation</span>
              </button>
            </div>
          </div>
        </div>
      )}

      {/* ⏻ Shutdown Confirmation Modal */}
      {modalType === 'SHUTDOWN' && (
        <div className="fixed inset-0 z-50 bg-black/60 backdrop-blur-xs flex items-center justify-center p-4">
          <div className="bg-white border-2 border-red-200 rounded-2xl max-w-md w-full p-6 space-y-4 shadow-2xl animate-in fade-in zoom-in-95 duration-200">
            <div className="flex items-center space-x-3 text-red-600">
              <div className="p-2.5 bg-red-100 rounded-xl">
                <Power className="w-6 h-6 text-red-600" />
              </div>
              <div>
                <h3 className="text-lg font-bold text-slate-900">Shut Down Workstation?</h3>
                <p className="text-xs text-slate-500 font-medium">Target: {computer?.hostname}</p>
              </div>
            </div>
            <p className="text-xs text-slate-700 leading-relaxed font-medium bg-red-50 p-3 rounded-xl border border-red-200">
              ⚠️ <strong className="text-red-900 font-bold">{computer?.hostname}</strong> will be powered down remotely. The computer will become OFFLINE and cannot be reached remotely until someone physically powers it back on.
            </p>
            <div className="flex items-center justify-end space-x-3 pt-3 border-t border-slate-200">
              <button
                onClick={() => setModalType(null)}
                className="px-4 py-2.5 rounded-xl text-xs font-bold text-slate-700 hover:bg-slate-100 border border-slate-300 transition-colors cursor-pointer"
              >
                Cancel
              </button>
              <button
                onClick={handleConfirmAction}
                className="px-5 py-2.5 rounded-xl text-xs font-extrabold bg-red-600 hover:bg-red-700 text-white shadow-md hover:shadow-lg transition-all cursor-pointer flex items-center gap-1.5"
              >
                <Power className="w-4 h-4" />
                <span>Confirm Shut Down Workstation</span>
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

export default RemotePowerManagement;
