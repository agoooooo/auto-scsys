import { useEffect, useMemo, useState } from "react";
import {
  Button,
  Card,
  Form,
  Input,
  InputNumber,
  Modal,
  Select,
  Space,
  Table,
  Tag,
  Typography,
  message,
} from "antd";
import { PlusOutlined, ReloadOutlined } from "@ant-design/icons";
import {
  apiRequest,
  type AuditEvent,
  type Device,
  type RunRecord,
  type Session,
  type User,
} from "./api";

type Section = "users" | "devices" | "runs" | "audit";
type Props = { session: Session; section: Section };

export function ResourcesPage({ session, section }: Props) {
  const [users, setUsers] = useState<User[]>([]);
  const [devices, setDevices] = useState<Device[]>([]);
  const [runs, setRuns] = useState<RunRecord[]>([]);
  const [audit, setAudit] = useState<AuditEvent[]>([]);
  const [loading, setLoading] = useState(true);
  const [filter, setFilter] = useState("");
  const [createOpen, setCreateOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [notice, contextHolder] = message.useMessage();
  const [form] = Form.useForm();

  const title: Record<Section, string> = {
    users: "用户管理",
    devices: "设备管理",
    runs: "运行记录",
    audit: "操作审计",
  };

  async function reload() {
    setLoading(true);
    try {
      if (section === "users") setUsers(await apiRequest<User[]>(session, "/users"));
      if (section === "devices") setDevices(await apiRequest<Device[]>(session, "/devices"));
      if (section === "runs") setRuns(await apiRequest<RunRecord[]>(session, "/runs"));
      if (section === "audit") setAudit(await apiRequest<AuditEvent[]>(session, "/admin/audit"));
    } catch (error) {
      notice.error(error instanceof Error ? error.message : "数据加载失败");
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    setFilter("");
    void reload();
  }, [section, session.token]);

  async function createUser(values: { name: string; email: string; password: string; device_limit: number }) {
    setBusy(true);
    try {
      await apiRequest(session, "/users", {
        method: "POST",
        body: JSON.stringify(values),
      });
      notice.success("用户已创建");
      setCreateOpen(false);
      form.resetFields();
      await reload();
    } catch (error) {
      notice.error(error instanceof Error ? error.message : "用户创建失败");
    } finally {
      setBusy(false);
    }
  }

  async function updateUser(user: User, update: Partial<Pick<User, "status" | "device_limit">>) {
    try {
      await apiRequest(session, `/users/${encodeURIComponent(user.id)}`, {
        method: "PATCH",
        body: JSON.stringify(update),
      });
      notice.success("用户信息已更新");
      await reload();
    } catch (error) {
      notice.error(error instanceof Error ? error.message : "用户更新失败");
    }
  }

  async function updateDevice(device: Device, status: Device["status"]) {
    try {
      await apiRequest(session, `/devices/${encodeURIComponent(device.id)}`, {
        method: "PATCH",
        body: JSON.stringify({ status }),
      });
      notice.success("设备状态已更新");
      await reload();
    } catch (error) {
      notice.error(error instanceof Error ? error.message : "设备更新失败");
    }
  }

  const filteredUsers = useMemo(
    () => users.filter((item) => `${item.name} ${item.email}`.toLowerCase().includes(filter.toLowerCase())),
    [users, filter],
  );
  const filteredDevices = useMemo(
    () => devices.filter((item) => `${item.name} ${item.model} ${item.user_name}`.toLowerCase().includes(filter.toLowerCase())),
    [devices, filter],
  );
  const filteredRuns = useMemo(
    () => runs.filter((item) => `${item.task_name} ${item.device_name} ${item.user_name} ${item.status}`.toLowerCase().includes(filter.toLowerCase())),
    [runs, filter],
  );
  const filteredAudit = useMemo(
    () => audit.filter((item) => `${item.actor} ${item.action} ${item.resource_type} ${item.resource_id}`.toLowerCase().includes(filter.toLowerCase())),
    [audit, filter],
  );

  const userColumns = [
    { title: "用户", dataIndex: "name", render: (_: string, item: User) => <Space direction="vertical" size={0}><Typography.Text strong>{item.name}</Typography.Text><Typography.Text type="secondary">{item.email}</Typography.Text></Space> },
    { title: "角色", dataIndex: "role", render: (value: string) => value === "admin" ? <Tag color="purple">管理员</Tag> : <Tag>普通用户</Tag> },
    { title: "设备上限", dataIndex: "device_limit", render: (value: number, item: User) => item.role === "admin" ? "—" : <InputNumber min={1} max={50} value={value} onPressEnter={(event) => void updateUser(item, { device_limit: Number((event.target as HTMLInputElement).value) })} onBlur={(event) => { const next = Number(event.target.value); if (next > 0 && next !== value) void updateUser(item, { device_limit: next }); }} /> },
    { title: "状态", dataIndex: "status", render: (value: User["status"]) => <Tag color={value === "active" ? "green" : "red"}>{value === "active" ? "正常" : "已停用"}</Tag> },
    { title: "操作", render: (_: unknown, item: User) => item.role === "admin" ? "—" : <Button type="link" danger={item.status === "active"} onClick={() => Modal.confirm({ title: item.status === "active" ? "停用此用户？" : "重新启用此用户？", content: item.status === "active" ? "停用将撤销会话、设备授权和待处理运行请求。" : "该用户将重新获得登录和使用权限。", okText: item.status === "active" ? "停用账号" : "启用账号", cancelText: "取消", onOk: () => updateUser(item, { status: item.status === "active" ? "disabled" : "active" }) })}>{item.status === "active" ? "停用" : "启用"}</Button> },
  ];

  const deviceColumns = [
    { title: "设备", dataIndex: "name", render: (_: string, item: Device) => <Space direction="vertical" size={0}><Typography.Text strong>{item.name}</Typography.Text><Typography.Text type="secondary">{item.model}</Typography.Text></Space> },
    { title: "所属用户", dataIndex: "user_name" },
    { title: "App 版本", dataIndex: "app_version" },
    { title: "状态", dataIndex: "status", render: (value: Device["status"]) => <Tag color={value === "online" ? "green" : value === "offline" ? "default" : "red"}>{value === "online" ? "在线" : value === "offline" ? "离线" : "已停用"}</Tag> },
    { title: "最近活跃", dataIndex: "last_active", render: (value: string) => value ? new Date(value).toLocaleString() : "—" },
    { title: "操作", render: (_: unknown, item: Device) => item.status === "disabled" ? "—" : <Select aria-label={`设备状态 ${item.name}`} value={item.status} style={{ width: 110 }} onChange={(value: Device["status"]) => Modal.confirm({ title: value === "disabled" ? "停用此设备？" : "更改设备状态？", content: value === "disabled" ? "停用会撤销该设备的待处理请求和授权；不保证中止已经开始的本地任务。" : "手动更改状态仅用于运维操作，在线状态通常由设备心跳更新。", okText: "确认", cancelText: "取消", onOk: () => updateDevice(item, value) })} options={[{ value: "online", label: "在线" }, { value: "offline", label: "离线" }, { value: "disabled", label: "停用" }]} /> },
  ];

  const runColumns = [
    { title: "任务", dataIndex: "task_name", render: (value: string, item: RunRecord) => <Space direction="vertical" size={0}><Typography.Text strong>{value}</Typography.Text><Typography.Text type="secondary">{item.version}</Typography.Text></Space> },
    { title: "设备", dataIndex: "device_name" },
    { title: "用户", dataIndex: "user_name" },
    { title: "状态", dataIndex: "status", render: (value: string) => <Tag color={value === "success" ? "green" : value === "running" ? "blue" : value === "failure" ? "red" : "default"}>{value}</Tag> },
    { title: "开始时间", dataIndex: "started_at", render: (value: string) => value ? new Date(value).toLocaleString() : "—" },
    { title: "结果摘要", dataIndex: "result_summary", ellipsis: true },
    { title: "运行 ID", dataIndex: "id", render: (value: string) => <Typography.Text code copyable>{value.slice(0, 12)}…</Typography.Text> },
  ];

  const auditColumns = [
    { title: "时间", dataIndex: "created_at", render: (value: string) => new Date(value).toLocaleString() },
    { title: "操作人", dataIndex: "actor" },
    { title: "操作", dataIndex: "action" },
    { title: "资源", dataIndex: "resource_type" },
    { title: "资源 ID", dataIndex: "resource_id", render: (value: string) => <Typography.Text code copyable>{value}</Typography.Text> },
  ];

  return (
    <div className="page-stack">
      {contextHolder}
      <div className="page-heading">
        <div>
          <Typography.Title level={2}>{title[section]}</Typography.Title>
          <Typography.Paragraph type="secondary">
            {section === "audit" ? "追踪管理员关键操作，审计记录只读" : "服务端实时数据；变更操作会由 API 做权限校验并写入审计"}
          </Typography.Paragraph>
        </div>
        <Space>
          {section === "users" && <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>创建用户</Button>}
          <Button icon={<ReloadOutlined />} onClick={() => void reload()}>刷新</Button>
        </Space>
      </div>
      <Card bordered={false}>
        <div className="table-toolbar">
          <Input.Search allowClear value={filter} onChange={(event) => setFilter(event.target.value)} placeholder={`搜索${title[section]}`} style={{ maxWidth: 360 }} />
        </div>
        {section === "users" && (
          <Table<User>
            rowKey="id"
            loading={loading}
            columns={userColumns}
            dataSource={filteredUsers}
            pagination={{ pageSize: 10, showSizeChanger: true, showTotal: (total) => `共 ${total} 条` }}
            locale={{ emptyText: "没有用户数据" }}
          />
        )}
        {section === "devices" && (
          <Table<Device>
            rowKey="id"
            loading={loading}
            columns={deviceColumns}
            dataSource={filteredDevices}
            pagination={{ pageSize: 10, showSizeChanger: true, showTotal: (total) => `共 ${total} 条` }}
            locale={{ emptyText: "没有设备数据" }}
          />
        )}
        {section === "runs" && (
          <Table<RunRecord>
            rowKey="id"
            loading={loading}
            columns={runColumns}
            dataSource={filteredRuns}
            pagination={{ pageSize: 10, showSizeChanger: true, showTotal: (total) => `共 ${total} 条` }}
            locale={{ emptyText: "没有运行数据" }}
          />
        )}
        {section === "audit" && (
          <Table<AuditEvent>
            rowKey="id"
            loading={loading}
            columns={auditColumns}
            dataSource={filteredAudit}
            pagination={{ pageSize: 10, showSizeChanger: true, showTotal: (total) => `共 ${total} 条` }}
            locale={{ emptyText: "没有审计数据" }}
          />
        )}
      </Card>
      <Modal
        title="创建普通用户"
        open={createOpen}
        onCancel={() => setCreateOpen(false)}
        onOk={() => form.submit()}
        okText="创建用户"
        cancelText="取消"
        confirmLoading={busy}
        destroyOnClose
      >
        <Form form={form} layout="vertical" initialValues={{ device_limit: 2 }} onFinish={(values) => void createUser(values)}>
          <Form.Item name="name" label="姓名" rules={[{ required: true, max: 80 }]}><Input maxLength={80} /></Form.Item>
          <Form.Item name="email" label="邮箱" rules={[{ required: true, type: "email" }]}><Input autoComplete="off" /></Form.Item>
          <Form.Item name="password" label="初始密码" rules={[{ required: true, min: 6, max: 256 }]}><Input.Password autoComplete="new-password" /></Form.Item>
          <Form.Item name="device_limit" label="设备数量上限" rules={[{ required: true }]}><InputNumber min={1} max={50} /></Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
