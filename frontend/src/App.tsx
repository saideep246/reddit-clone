import { Route, Routes } from 'react-router-dom';
import { useAuth } from './auth/AuthContext';
import { Layout } from './components/Layout';
import { ChatRoom } from './pages/ChatRoom';
import { ChatRoomList } from './pages/ChatRoomList';
import { CommunityDiscovery } from './pages/CommunityDiscovery';
import { CommunityPage } from './pages/CommunityPage';
import { CreateCommunity } from './pages/CreateCommunity';
import { Home } from './pages/Home';
import { Login } from './pages/Login';
import { ModerationDashboard } from './pages/ModerationDashboard';
import { NotificationsInbox } from './pages/NotificationsInbox';
import { PostDetail } from './pages/PostDetail';
import { PostSubmit } from './pages/PostSubmit';
import { PostRedirect } from './pages/PostRedirect';
import { ScheduledPosts } from './pages/ScheduledPosts';
import { Register } from './pages/Register';
import { Search } from './pages/Search';
import { Settings } from './pages/Settings';
import { UserConnections } from './pages/UserConnections';
import { UserProfile } from './pages/UserProfile';

function App() {
  const { initializing } = useAuth();

  // Blank until the silent refresh (against the httpOnly cookie) resolves, so a returning user
  // never sees a flash of "logged out" before their session is restored.
  if (initializing) {
    return null;
  }

  return (
    <Routes>
      <Route element={<Layout />}>
        <Route path="/" element={<Home />} />
        <Route path="/chat" element={<ChatRoomList />} />
        <Route path="/chat/:roomId" element={<ChatRoom />} />
        <Route path="/login" element={<Login />} />
        <Route path="/register" element={<Register />} />
        <Route path="/r/:communityName/comments/:postId" element={<PostDetail />} />
        <Route path="/r/:communityName/submit" element={<PostSubmit />} />
        <Route path="/r/:communityName/mod" element={<ModerationDashboard />} />
        <Route path="/r/:communityName" element={<CommunityPage />} />
        <Route path="/communities" element={<CommunityDiscovery />} />
        <Route path="/communities/create" element={<CreateCommunity />} />
        <Route path="/notifications" element={<NotificationsInbox />} />
        <Route path="/search" element={<Search />} />
        <Route path="/settings" element={<Settings />} />
        <Route path="/submit" element={<PostSubmit />} />
        <Route path="/scheduled" element={<ScheduledPosts />} />
        <Route path="/p/:postId" element={<PostRedirect />} />
        <Route path="/user/:username" element={<UserProfile />} />
        <Route path="/user/:username/followers" element={<UserConnections mode="followers" />} />
        <Route path="/user/:username/following" element={<UserConnections mode="following" />} />
      </Route>
    </Routes>
  );
}

export default App;
