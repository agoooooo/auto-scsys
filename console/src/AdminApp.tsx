import { lazy, Suspense, useEffect, useState } from "react";
import {
  Alert,
  Avatar,
  Card,
  Col,
  Dropdown,
  Layout,
  Menu,
  Row,
  Skeleton,
  Spin,
  Statistic,
  Tag,
  Typography,
  message,
} from "antd";
import {
  AuditOutlined,
  CodeOutlined,
  DashboardOutlined,
  DesktopOutlined,
  LogoutOutlined,
  SafetyCertificateOutlined,
  TeamOutlined,
  UnorderedListOutlined,
} from "@ant-design/icons";
import {
  apiRequest,
  clearSession,
  readSession,
  type Device,
  type RunRecord,
  type Session,
  type Task,
  type User,
} from "./api";
import { Login } from "./Login";
import { ResourcesPage } from "./ResourcesPage";
const TasksPage = lazy(() => import("./TasksPage").then((module) => ({ default: module.TasksPage })));

type Page = "overview" | "users" | "devices" | "tasks" | "runs" | "audit";

const pageLabels: Record<Page, string> = {
  overview: "总览",
  users: "用户管理",
  devices: "设备管理",
  tasks: "自动化任务",
  runs: "运行记录",
  audit: "操作审计",
};

function sessionFromStorage(): Session | null {
  return readSession(window.localStorage) ?? readSession(window.sessionStorage);
}

export function AdminApp() {
  const [session, setSession] = useState<Session | null>(sessionFromStorage);
  const [page, setPage] = useState<Page>("overview");
  const [checkingSession, setCheckingSession] = useState(Boolean(session));
  const [notice, contextHolder] = message.useMessage();

  useEffect(() => {
    const onExpired = () => {
      setSession(null);
      setPage("overview");
      notice.warning("登录会话已失效，请重新登录");
    };
    window.addEventListener("flowpilot:session-expired", onExpired);
    return () => window.removeEventListener("flowpilot:session-expired", onExpired);
  }, []);

  useEffect(() => {
    if (!session) {
      setCheckingSession(false);
      return;
    }
    let active = true;
    setCheckingSession(true);
    apiRequest<User>(session, "/me")
      .then((user) => {
        if (!active) return;
        if (user.role !== "admin") {
          clearSession();
          setSession(null);
          notice.error("此账号没有后台管理员权限");
          return;
        }
        setSession({ ...session, user });
      })
      .catch((error: unknown) => {
        if (!active) return;
        clearSession();
        setSession(null);
        if (error instanceof Error) notice.error(error.message);
      })
      .finally(() => {
        if (active) setCheckingSession(false);
      });
    return () => {
      active = false;
    };
  }, []);

  async function logout() {
    if (session) {
      try {
        await apiRequest(session, "/auth/logout", { method: "POST" });
      } catch (error) {
        notice.error(`本地已退出，但服务端会话撤销失败：${error instanceof Error ? error.message : "网络错误"}`);
      }
    }
    clearSession();
    setSession(null);
    setPage("overview");
  }

  if (checkingSession) {
    return <div className="page-spinner"><Spin size="large" /></div>;
  }
  if (!session) return <Login onLogin={setSession} />;

  const menuItems = [
    { key: "overview", icon: <DashboardOutlined />, label: "运营总览" },
    { key: "users", icon: <TeamOutlined />, label: "用户管理" },
    { key: "devices", icon: <DesktopOutlined />, label: "设备管理" },
    { key: "tasks", icon: <CodeOutlined />, label: "脚本与任务" },
    { key: "runs", icon: <UnorderedListOutlined />, label: "运行记录" },
    { key: "audit", icon: <AuditOutlined />, label: "操作审计" },
  ];

  return (
    <Layout className="admin-layout">
      {contextHolder}
      <Layout.Sider
        breakpoint="lg"
        collapsedWidth={0}
        width={250}
        className="admin-sider"
      >
        <div className="sider-brand">
          <span className="brand-symbol">F</span>
          <span><strong>FlowPilot</strong><small>管理控制台</small></span>
        </div>
        <div className="sider-caption">平台管理</div>
        <Menu
          mode="inline"
          selectedKeys={[page]}
          items={menuItems}
          onClick={({ key }) => setPage(key as Page)}
        />
        <div className="sider-footer">
          <SafetyCertificateOutlined />
          <span>所有操作均由服务端鉴权并记录</span>
        </div>
      </Layout.Sider>
      <Layout className="main-layout">
        <Layout.Header className="top-header">
          <div>
            <Typography.Text type="secondary">FlowPilot /</Typography.Text>
            <Typography.Text strong> {pageLabels[page]}</Typography.Text>
          </div>
          <Dropdown
            menu={{
              items: [{ key: "logout", icon: <LogoutOutlined />, label: "退出登录", danger: true }],
              onClick: () => void logout(),
            }}
            trigger={["click"]}
          >
            <button type="button" className="account-trigger">
              <Avatar size={34} style={{ backgroundColor: "#dff4e8", color: "#087744" }}>
                {session.user.name.slice(0, 1)}
              </Avatar>
              <span><strong>{session.user.name}</strong><small>系统管理员</small></span>
            </button>
          </Dropdown>
        </Layout.Header>
        <Layout.Content className="main-content">
          {page === "overview" && <Overview session={session} onNavigate={setPage} />}
          {page === "tasks" && (
            <Suspense fallback={<Card bordered={false}><Skeleton active /></Card>}>
              <TasksPage session={session} />
            </Suspense>
          )}
          {page === "users" && <ResourcesPage session={session} section="users" />}
          {page === "devices" && <ResourcesPage session={session} section="devices" />}
          {page === "runs" && <ResourcesPage session={session} section="runs" />}
          {page === "audit" && <ResourcesPage session={session} section="audit" />}
        </Layout.Content>
      </Layout>
    </Layout>
  );
}

function Overview({ session, onNavigate }: { session: Session; onNavigate: (page: Page) => void }) {
  const [counts, setCounts] = useState({ users: 0, devices: 0, online: 0, tasks: 0, activeRuns: 0 });
  const [runs, setRuns] = useState<RunRecord[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  useEffect(() => {
    let active = true;
    Promise.all([
      apiRequest<User[]>(session, "/users"),
      apiRequest<Device[]>(session, "/devices"),
      apiRequest<Task[]>(session, "/tasks"),
      apiRequest<RunRecord[]>(session, "/runs"),
    ])
      .then(([users, devices, tasks, allRuns]) => {
        if (!active) return;
        setCounts({
          users: users.filter((user) => user.role === "user").length,
          devices: devices.filter((device) => device.status !== "disabled").length,
          online: devices.filter((device) => device.status === "online").length,
          tasks: tasks.length,
          activeRuns: allRuns.filter((run) => ["running", "pending", "queued"].includes(run.status)).length,
        });
        setRuns(allRuns.slice(0, 6));
      })
      .catch((reason: unknown) => {
        if (active) setError(reason instanceof Error ? reason.message : "总览数据加载失败");
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [session.token]);

  return (
    <div className="page-stack">
      <div className="page-heading">
        <div>
          <Typography.Title level={2}>运营总览</Typography.Title>
          <Typography.Paragraph type="secondary">
            平台设备与自动化运行的实时概况
          </Typography.Paragraph>
        </div>
        <Tag color="green">生产管理控制台</Tag>
      </div>
      {error && <Alert type="error" showIcon message="部分数据暂不可用" description={error} />}
      <Row gutter={[16, 16]}>
        <Metric title="普通用户" value={counts.users} loading={loading} onClick={() => onNavigate("users")} />
        <Metric title="已启用设备" value={counts.devices} loading={loading} onClick={() => onNavigate("devices")} />
        <Metric title="在线设备" value={counts.online} loading={loading} onClick={() => onNavigate("devices")} />
        <Metric title="可见任务" value={counts.tasks} loading={loading} onClick={() => onNavigate("tasks")} />
        <Metric title="活动运行" value={counts.activeRuns} loading={loading} onClick={() => onNavigate("runs")} />
      </Row>
      <Card
        bordered={false}
        title="最近运行"
        extra={<Typography.Link onClick={() => onNavigate("runs")}>查看全部</Typography.Link>}
      >
        {loading ? <div className="inline-spinner"><Spin /></div> : runs.length === 0 ? (
          <Typography.Text type="secondary">暂无运行记录</Typography.Text>
        ) : (
          <div className="recent-runs">
            {runs.map((run) => (
              <div className="recent-run-row" key={run.id}>
                <div>
                  <Typography.Text strong>{run.task_name}</Typography.Text>
                  <Typography.Text type="secondary">{run.device_name} · {run.user_name} · {run.version}</Typography.Text>
                </div>
                <Tag color={run.status === "success" ? "green" : run.status === "running" ? "blue" : run.status === "failure" ? "red" : "default"}>
                  {run.status}
                </Tag>
              </div>
            ))}
          </div>
        )}
      </Card>
    </div>
  );
}

function Metric({
  title,
  value,
  loading,
  onClick,
}: {
  title: string;
  value: number;
  loading: boolean;
  onClick: () => void;
}) {
  return (
    <Col xs={24} sm={12} xl={6}>
      <Card bordered={false} className="metric-card" hoverable onClick={onClick}>
        <Statistic title={title} value={value} loading={loading} />
      </Card>
    </Col>
  );
}
