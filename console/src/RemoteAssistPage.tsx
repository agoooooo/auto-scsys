import { useEffect, useRef, useState, type PointerEvent as ReactPointerEvent } from "react";
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Empty,
  Select,
  Space,
  Spin,
  Tag,
  Typography,
  message,
} from "antd";
import {
  ArrowLeftOutlined,
  HomeOutlined,
  ReloadOutlined,
  StopOutlined,
  VideoCameraOutlined,
} from "@ant-design/icons";
import {
  apiRequest,
  createRemoteAssistSession,
  endRemoteAssistSession,
  getRemoteAssistControllerTicket,
  getRemoteAssistFrame,
  getRemoteAssistSession,
  sendRemoteAssistInput,
  type Device,
  type RemoteAssistSession,
  type Session,
} from "./api";

type Props = { session: Session };
type PointerStart = { x: number; y: number; at: number; pointerId: number };

const sessionStatus: Record<RemoteAssistSession["status"], { label: string; color: string }> = {
  pending: { label: "等待设备用户确认", color: "gold" },
  active: { label: "协助进行中", color: "green" },
  rejected: { label: "设备用户已拒绝", color: "default" },
  ended: { label: "会话已结束", color: "default" },
  expired: { label: "会话已超时", color: "default" },
};

export function RemoteAssistPage({ session }: Props) {
  const [devices, setDevices] = useState<Device[]>([]);
  const [selectedDeviceId, setSelectedDeviceId] = useState<string>();
  const [durationSeconds, setDurationSeconds] = useState(300);
  const [activeSession, setActiveSession] = useState<RemoteAssistSession | null>(null);
  const [ticket, setTicket] = useState<string>();
  const [frameUrl, setFrameUrl] = useState<string>();
  const [frameLoading, setFrameLoading] = useState(false);
  const [starting, setStarting] = useState(false);
  const [error, setError] = useState("");
  const pointerStart = useRef<PointerStart | null>(null);
  const sequence = useRef(0);
  const latestUrl = useRef<string | undefined>(undefined);
  const [notice, contextHolder] = message.useMessage();

  useEffect(() => {
    let active = true;
    apiRequest<Device[]>(session, "/devices")
      .then((items) => {
        if (!active) return;
        const online = items.filter((device) =>
          device.status === "online" &&
          (session.user.role !== "admin" || device.account_status === "active"),
        );
        setDevices(online);
        if (!selectedDeviceId && online.length === 1) setSelectedDeviceId(online[0].id);
      })
      .catch((reason: unknown) => {
        if (active) setError(reason instanceof Error ? reason.message : "设备列表加载失败");
      });
    return () => {
      active = false;
    };
  }, [session.token]);

  useEffect(() => {
    if (!activeSession) return;
    let active = true;
    let timer = 0;
    const poll = async () => {
      try {
        const latest = await getRemoteAssistSession(session, activeSession.id);
        if (!active) return;
        setActiveSession(latest);
        if (latest.status === "active") {
          timer = window.setTimeout(() => void poll(), 1000);
        } else if (latest.status === "pending") {
          timer = window.setTimeout(() => void poll(), 1500);
        } else {
          setTicket(undefined);
        }
      } catch (reason) {
        if (!active) return;
        setError(reason instanceof Error ? reason.message : "会话状态读取失败");
        timer = window.setTimeout(() => void poll(), 3000);
      }
    };
    timer = window.setTimeout(() => void poll(), 700);
    return () => {
      active = false;
      window.clearTimeout(timer);
    };
  }, [activeSession?.id, activeSession?.status, session.token]);

  useEffect(() => {
    if (!activeSession || activeSession.status !== "active") return;
    let active = true;
    getRemoteAssistControllerTicket(session, activeSession.id)
      .then((value) => {
        if (active) {
          setTicket(value);
          setError("");
        }
      })
      .catch((reason: unknown) => {
        if (active) setError(reason instanceof Error ? reason.message : "远程协助连接失败");
      });
    return () => {
      active = false;
    };
  }, [activeSession?.id, activeSession?.status, session.token]);

  useEffect(() => {
    if (!activeSession || activeSession.status !== "active" || !ticket) return;
    let active = true;
    let timer = 0;
    const poll = async () => {
      try {
        const next = await getRemoteAssistFrame(session, activeSession.id, ticket, sequence.current);
        if (!active) return;
        if (next) {
          sequence.current = next.sequence;
          const url = URL.createObjectURL(next.blob);
          if (latestUrl.current) URL.revokeObjectURL(latestUrl.current);
          latestUrl.current = url;
          setFrameUrl(url);
        }
        setFrameLoading(false);
        setError("");
        timer = window.setTimeout(() => void poll(), 350);
      } catch (reason) {
        if (!active) return;
        setFrameLoading(false);
        setError(reason instanceof Error ? reason.message : "远程画面读取失败");
        timer = window.setTimeout(() => void poll(), 1500);
      }
    };
    setFrameLoading(true);
    timer = window.setTimeout(() => void poll(), 200);
    return () => {
      active = false;
      window.clearTimeout(timer);
    };
  }, [activeSession?.id, activeSession?.status, session.token, ticket]);

  useEffect(() => () => {
    if (latestUrl.current) URL.revokeObjectURL(latestUrl.current);
  }, []);

  async function startSession() {
    if (!selectedDeviceId || starting) return;
    setStarting(true);
    setError("");
    sequence.current = 0;
    try {
      const created = await createRemoteAssistSession(session, selectedDeviceId, durationSeconds);
      setActiveSession(created);
      notice.success("请求已发送，请等待设备用户确认并授权屏幕共享");
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "无法发起远程协助");
    } finally {
      setStarting(false);
    }
  }

  async function endSession() {
    if (!activeSession) return;
    try {
      const result = await endRemoteAssistSession(session, activeSession.id);
      setActiveSession({ ...activeSession, status: result.status as RemoteAssistSession["status"] });
      setTicket(undefined);
      notice.success("远程协助已结束");
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "结束远程协助失败");
    }
  }

  async function sendInput(input: Parameters<typeof sendRemoteAssistInput>[3]) {
    if (!activeSession || !ticket) return;
    try {
      await sendRemoteAssistInput(session, activeSession.id, ticket, input);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "远程操作发送失败");
    }
  }

  function onPointerDown(event: ReactPointerEvent<HTMLImageElement>) {
    if (!ticket) return;
    event.preventDefault();
    event.currentTarget.setPointerCapture(event.pointerId);
    const bounds = event.currentTarget.getBoundingClientRect();
    pointerStart.current = {
      x: Math.min(1, Math.max(0, (event.clientX - bounds.left) / bounds.width)),
      y: Math.min(1, Math.max(0, (event.clientY - bounds.top) / bounds.height)),
      at: Date.now(),
      pointerId: event.pointerId,
    };
  }

  function onPointerUp(event: ReactPointerEvent<HTMLImageElement>) {
    const start = pointerStart.current;
    if (!start || start.pointerId !== event.pointerId) return;
    pointerStart.current = null;
    event.preventDefault();
    const bounds = event.currentTarget.getBoundingClientRect();
    const endX = Math.min(1, Math.max(0, (event.clientX - bounds.left) / bounds.width));
    const endY = Math.min(1, Math.max(0, (event.clientY - bounds.top) / bounds.height));
    const duration = Math.min(2000, Math.max(100, Date.now() - start.at));
    if (Math.hypot(endX - start.x, endY - start.y) < 0.018) {
      void sendInput({ type: "tap", x: start.x, y: start.y });
    } else {
      void sendInput({
        type: "swipe",
        x: start.x,
        y: start.y,
        end_x: endX,
        end_y: endY,
        duration_ms: duration,
      });
    }
  }

  const hasLiveSession = activeSession && ["pending", "active"].includes(activeSession.status);

  return (
    <div className="page-stack">
      {contextHolder}
      <div className="page-heading">
        <div>
          <Typography.Title level={2}>远程协助</Typography.Title>
          <Typography.Paragraph type="secondary">
            经设备用户明确同意后，临时查看屏幕并进行基础触控操作
          </Typography.Paragraph>
        </div>
        <Tag color="blue">最长 10 分钟 · 不录制画面</Tag>
      </div>
      <Alert
        type="warning"
        showIcon
        message="设备用户必须同意并通过 Android 屏幕共享授权"
        description="设备端会持续显示协助者和结束入口。会话结束或超时后立即撤销通道凭据；画面只在服务内存中短暂中转，不写入数据库或录像。请勿在会话期间查看与协助无关的敏感信息。"
      />
      {error && <Alert type="error" showIcon message="远程协助状态" description={error} />}
      {!hasLiveSession ? (
        <Card bordered={false} title="发起会话">
          <Space direction="vertical" size="middle" style={{ width: "100%" }}>
            <Select
              aria-label="目标受控设备"
              value={selectedDeviceId}
              onChange={setSelectedDeviceId}
              placeholder="选择在线设备"
              options={devices.map((device) => ({
                value: device.id,
                label: session.user.role === "admin"
                  ? `${device.name} · ${device.model} · ${device.user_name}`
                  : `${device.name} · ${device.model}`,
              }))}
              notFoundContent="当前没有在线设备"
              style={{ width: "100%", maxWidth: 560 }}
            />
            <Select
              aria-label="远程协助时长"
              value={durationSeconds}
              onChange={setDurationSeconds}
              options={[
                { value: 60, label: "1 分钟" },
                { value: 300, label: "5 分钟" },
                { value: 600, label: "10 分钟" },
              ]}
              style={{ width: 160 }}
            />
            <Button
              type="primary"
              icon={<VideoCameraOutlined />}
              disabled={!selectedDeviceId}
              loading={starting}
              onClick={() => void startSession()}
            >
              请求设备用户确认
            </Button>
          </Space>
        </Card>
      ) : (
        <Card
          bordered={false}
          title={<Space><span>{activeSession.device_name}</span><Tag color={sessionStatus[activeSession.status].color}>{sessionStatus[activeSession.status].label}</Tag></Space>}
          extra={<Button danger icon={<StopOutlined />} onClick={() => void endSession()}>结束协助</Button>}
        >
          <Descriptions size="small" column={{ xs: 1, sm: 2 }}>
            <Descriptions.Item label="设备">{activeSession.device_name} · {activeSession.device_model}</Descriptions.Item>
            <Descriptions.Item label="协助者">{activeSession.requested_by_name}</Descriptions.Item>
            <Descriptions.Item label="自动结束">{new Date(activeSession.expires_at * 1000).toLocaleString()}</Descriptions.Item>
            <Descriptions.Item label="会话 ID"><Typography.Text code copyable>{activeSession.id}</Typography.Text></Descriptions.Item>
          </Descriptions>
          {activeSession.status === "pending" ? (
            <div className="inline-spinner"><Spin tip="等待设备用户确认并完成 Android 屏幕共享授权…" /></div>
          ) : activeSession.status === "active" ? (
            <div>
              <Space wrap style={{ marginBlock: 12 }}>
                <Button icon={<ArrowLeftOutlined />} disabled={!ticket} onClick={() => void sendInput({ type: "back" })}>返回</Button>
                <Button icon={<HomeOutlined />} disabled={!ticket} onClick={() => void sendInput({ type: "home" })}>主页</Button>
                <Button icon={<ReloadOutlined />} onClick={() => { sequence.current = 0; setFrameLoading(true); }}>重新取帧</Button>
                {frameLoading && <Spin size="small" />}
              </Space>
              {frameUrl ? (
                <img
                  src={frameUrl}
                  alt={`${activeSession.device_name} 的实时屏幕画面`}
                  draggable={false}
                  onPointerDown={onPointerDown}
                  onPointerUp={onPointerUp}
                  onPointerCancel={() => { pointerStart.current = null; }}
                  style={{
                    display: "block",
                    width: "min(100%, 540px)",
                    maxHeight: "70vh",
                    marginInline: "auto",
                    objectFit: "contain",
                    background: "#111",
                    borderRadius: 12,
                    touchAction: "none",
                    userSelect: "none",
                    cursor: "crosshair",
                  }}
                />
              ) : (
                <Empty description="等待受控设备发送首帧" />
              )}
              <Typography.Paragraph type="secondary" style={{ textAlign: "center", marginTop: 10 }}>
                在画面上按下并移动可发送点击或滑动。设备端可以随时结束协助。
              </Typography.Paragraph>
            </div>
          ) : (
            <Alert type="info" showIcon message={sessionStatus[activeSession.status].label} />
          )}
        </Card>
      )}
    </div>
  );
}
