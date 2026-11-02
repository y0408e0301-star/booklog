import { Navigate, Route, Routes } from 'react-router'
import { AuthProvider } from './auth/AuthProvider'
import { GuestOnly, RequireAuth } from './auth/guards'
import Layout from './components/Layout'
import { ToastProvider } from './components/ToastProvider'
import HomePage from './pages/HomePage'
import LoginPage from './pages/LoginPage'
import SignupPage from './pages/SignupPage'

// 画面と URL（画面一覧 1 章）。BrowserRouter（テストでは MemoryRouter）の中で使う。
// G-4〜G-10 は、それぞれの機能を作るときに足す（開発計画書 3.1）
export default function App() {
  return (
    <AuthProvider>
      <ToastProvider>
        <Routes>
          <Route element={<GuestOnly />}>
            <Route path="/login" element={<LoginPage />} />
            <Route path="/signup" element={<SignupPage />} />
          </Route>
          <Route element={<RequireAuth />}>
            {/* G-3〜G-10 はヘッダーつき */}
            <Route element={<Layout />}>
              <Route path="/" element={<HomePage />} />
            </Route>
          </Route>
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </ToastProvider>
    </AuthProvider>
  )
}
