import { useEffect, useState } from "react";
import {
  Button,
  Card,
  Form,
  Input,
  InputNumber,
  Select,
  Space,
  Table,
  Tag,
  Typography,
  Upload,
  message,
} from "antd";
import { UploadOutlined } from "@ant-design/icons";
import {
  apiRequest,
  type AppRelease,
  type Session,
} from "./api";

type ReleaseForm = {
  app_id: AppRelease["app_id"];
  version_name: string;
  version_code: number;
  notes: string;
};

export function AppReleasesPage({ session }: { session: Session }) {
  const [releases, setReleases] = useState<AppRelease[]>([]);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [apk, setApk] = useState<File | null>(null);
  const [notice, contextHolder] = message.useMessage();
  const [form] = Form.useForm<ReleaseForm>();
  const selectedAppId = Form.useWatch("app_id", form) ?? "controlled";

  useEffect(() => {
    const currentVersionCode = releases.find((release) => release.app_id === selectedAppId)?.version_code;
    form.setFieldValue("version_code", currentVersionCode ? currentVersionCode + 1 : undefined);
  }, [form, releases, selectedAppId]);

  async function reload() {
    setLoading(true);
    try {
      setReleases(await apiRequest<AppRelease[]>(session, "/admin/app-releases"));
    } catch (error) {
      notice.error(error instanceof Error ? error.message : "App 版本加载失败");
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void reload();
  }, [session.token]);

  async function publish(values: ReleaseForm) {
    if (!apk) {
      notice.error("请选择 APK 安装包");
      return;
    }
    const current = releases.find((release) => release.app_id === values.app_id);
    if (current?.version_code && values.version_code <= current.version_code) {
      notice.error(`versionCode 必须大于当前版本 ${current.version_code}`);
      return;
    }
    setBusy(true);
    try {
      const data = new FormData();
      data.set("app_id", values.app_id);
      data.set("version_name", values.version_name);
      data.set("version_code", String(values.version_code));
      data.set("notes", values.notes ?? "");
      data.set("apk", apk);
      await apiRequest(session, "/admin/app-releases", { method: "POST", body: data });
      notice.success("新版本已发布，设备前台检查更新时会收到更新");
      setApk(null);
      form.resetFields(["version_name", "version_code", "notes"]);
      await reload();
    } catch (error) {
      notice.error(error instanceof Error ? error.message : "App 发布失败");
    } finally {
      setBusy(false);
    }
  }

  const columns = [
    { title: "App", dataIndex: "app_name", render: (_: string, item: AppRelease) => <Space direction="vertical" size={0}><Typography.Text strong>{item.app_name}</Typography.Text><Typography.Text type="secondary">{item.package_name}</Typography.Text></Space> },
    { title: "版本", render: (_: unknown, item: AppRelease) => item.version_name ? <Tag color="green">{item.version_name} · {item.version_code}</Tag> : <Tag>尚未发布</Tag> },
    { title: "更新说明", dataIndex: "notes", render: (value?: string) => value || "—" },
    { title: "安装包大小", dataIndex: "content_size", render: (value?: number) => value ? `${(value / 1024 / 1024).toFixed(1)} MiB` : "—" },
    { title: "发布时间", dataIndex: "published_at", render: (value?: string) => value ? new Date(value).toLocaleString() : "—" },
  ];

  return (
    <div className="page-stack">
      {contextHolder}
      <div className="page-heading">
        <div>
          <Typography.Title level={2}>App 自动更新</Typography.Title>
          <Typography.Paragraph type="secondary">
            发布控制端和受控端 APK。设备前台会自动检查并下载新版本，安装由 Android 系统确认。
          </Typography.Paragraph>
        </div>
      </div>
      <Card bordered={false} title="当前发布版本">
        <Table<AppRelease>
          rowKey="app_id"
          loading={loading}
          columns={columns}
          dataSource={releases}
          pagination={false}
          scroll={{ x: "max-content" }}
        />
      </Card>
      <Card bordered={false} title="发布新版本">
        <Form<ReleaseForm>
          form={form}
          layout="vertical"
          initialValues={{ app_id: "controlled", version_name: "", notes: "" }}
          onFinish={(values) => void publish(values)}
        >
          <Form.Item name="app_id" label="应用" rules={[{ required: true }]}>
            <Select
              options={[
                { value: "controlled", label: "FlowPilot 受控端" },
                { value: "controller", label: "FlowPilot 控制端" },
              ]}
            />
          </Form.Item>
          <Space align="start" size="middle" wrap style={{ width: "100%" }}>
            <Form.Item name="version_name" label="版本名称" rules={[{ required: true, pattern: /^\d+\.\d+\.\d+$/, message: "格式应为 1.2.3" }]}>
              <Input placeholder="例如 0.1.1" />
            </Form.Item>
            <Form.Item
              name="version_code"
              label="versionCode"
              rules={[{ required: true }]}
              extra="必须与 APK 的 versionCode 相同且高于设备已安装版本；可在对应模块的 build.gradle.kts 中查看。"
            >
              <InputNumber min={1} precision={0} />
            </Form.Item>
          </Space>
          <Form.Item label="APK 安装包" required>
            <Upload
              accept=".apk,application/vnd.android.package-archive"
              maxCount={1}
              beforeUpload={(file) => {
                setApk(file);
                return false;
              }}
              onRemove={() => setApk(null)}
              fileList={apk ? [{ uid: "app-release", name: apk.name, status: "done" }] : []}
            >
              <Button icon={<UploadOutlined />}>选择 APK</Button>
            </Upload>
            <Typography.Paragraph type="secondary" style={{ marginTop: 8 }}>
              最大 100 MiB。请使用与已安装应用相同的发布签名密钥并递增 versionCode；设备安装时由 Android 校验签名。
            </Typography.Paragraph>
          </Form.Item>
          <Form.Item name="notes" label="更新说明">
            <Input.TextArea maxLength={500} rows={3} />
          </Form.Item>
          <Button type="primary" htmlType="submit" loading={busy}>发布并启用</Button>
        </Form>
      </Card>
    </div>
  );
}
