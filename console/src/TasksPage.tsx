import { useEffect, useMemo, useState } from "react";
import { DiffEditor, Editor } from "@monaco-editor/react";
import "./monaco";
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Form,
  Input,
  Modal,
  Space,
  Table,
  Tag,
  Typography,
  message,
} from "antd";
import {
  CodeOutlined,
  FileAddOutlined,
  HistoryOutlined,
  PauseCircleOutlined,
  PlayCircleOutlined,
  SaveOutlined,
} from "@ant-design/icons";
import {
  apiRequest,
  publishScriptVersion,
  requestScriptSource,
  type Session,
  type Task,
} from "./api";
import { draftKey, matchesTaskSearch, nextPatchVersion } from "./policies";

type Props = { session: Session };
type EditorTarget = { task: Task; isNewTask: boolean } | null;

const taskStatus: Record<Task["status"], { label: string; color: string }> = {
  draft: { label: "草稿", color: "default" },
  published: { label: "已发布", color: "green" },
  paused: { label: "已暂停", color: "orange" },
};

function localDraft(taskId: string): { source: string; notes: string } | null {
  try {
    const value = sessionStorage.getItem(draftKey(taskId));
    return value ? JSON.parse(value) as { source: string; notes: string } : null;
  } catch {
    return null;
  }
}

export function TasksPage({ session }: Props) {
  const [tasks, setTasks] = useState<Task[]>([]);
  const [loading, setLoading] = useState(true);
  const [filter, setFilter] = useState("");
  const [editorTarget, setEditorTarget] = useState<EditorTarget>(null);
  const [showCreate, setShowCreate] = useState(false);
  const [createBusy, setCreateBusy] = useState(false);
  const [versionsTask, setVersionsTask] = useState<Task | null>(null);
  const [compareVersions, setCompareVersions] = useState<{ older: string; newer: string } | null>(null);
  const [compareSources, setCompareSources] = useState<{ original: string; modified: string } | null>(null);
  const [source, setSource] = useState("");
  const [version, setVersion] = useState("v1.0.0");
  const [filename, setFilename] = useState("main.py");
  const [notes, setNotes] = useState("");
  const [sourceLoading, setSourceLoading] = useState(false);
  const [publishing, setPublishing] = useState(false);
  const [notice, contextHolder] = message.useMessage();
  const [createForm] = Form.useForm<{
    name: string;
    description: string;
    category: string;
    capabilities: string;
  }>();
  const filteredTasks = useMemo(
    () => tasks.filter((task) => matchesTaskSearch(task, filter)),
    [filter, tasks],
  );

  async function reload() {
    setLoading(true);
    try {
      setTasks(await apiRequest<Task[]>(session, "/tasks"));
    } catch (error) {
      notice.error(error instanceof Error ? error.message : "任务加载失败");
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void reload();
  }, [session.token]);

  const columns = useMemo(() => [
    {
      title: "任务",
      dataIndex: "name",
      key: "name",
      render: (_: string, task: Task) => (
        <Space direction="vertical" size={0}>
          <Typography.Text strong>{task.name}</Typography.Text>
          <Typography.Text type="secondary" className="table-secondary">
            {task.description || task.category}
          </Typography.Text>
        </Space>
      ),
    },
    {
      title: "状态",
      dataIndex: "status",
      key: "status",
      render: (status: Task["status"]) => <Tag color={taskStatus[status].color}>{taskStatus[status].label}</Tag>,
    },
    {
      title: "当前版本",
      dataIndex: "active_version",
      key: "active_version",
      render: (activeVersion: string | null) => activeVersion || "—",
    },
    {
      title: "脚本版本",
      dataIndex: "versions",
      key: "versions",
      render: (items: Task["versions"]) => items?.length ?? 0,
    },
    {
      title: "可用设备",
      dataIndex: "assigned_device_count",
      key: "assigned_device_count",
      render: (count: number | undefined) => count ?? "—",
    },
    {
      title: "操作",
      key: "actions",
      render: (_: unknown, task: Task) => (
        <Space wrap>
          <Button icon={<CodeOutlined />} onClick={() => void openEditor(task)}>编写脚本</Button>
          <Button icon={<HistoryOutlined />} onClick={() => setVersionsTask(task)}>版本历史</Button>
          {task.status === "paused" ? (
            <Button
              type="link"
              icon={<PlayCircleOutlined />}
              onClick={() => void setTaskStatus(task, "published")}
            >
              恢复
            </Button>
          ) : (
            <Button
              type="link"
              danger
              icon={<PauseCircleOutlined />}
              onClick={() => void setTaskStatus(task, "paused")}
            >
              暂停
            </Button>
          )}
        </Space>
      ),
    },
  ], [session.token, tasks]);

  async function openEditor(task: Task, isNewTask = false) {
    setEditorTarget({ task, isNewTask });
    const saved = localDraft(task.id);
    setSource(saved?.source ?? "");
    setNotes(saved?.notes ?? "");
    setVersion(nextPatchVersion(task.active_version, task.versions.map((item) => item.version)));
    setFilename(task.versions?.[0]?.filename ?? "main.py");
    if (!isNewTask && task.active_version) {
      setSourceLoading(true);
      try {
        const original = await requestScriptSource(session, task.id, task.active_version);
        setSource(saved?.source ?? original);
      } catch (error) {
        notice.error(error instanceof Error ? error.message : "无法读取当前版本源码");
      } finally {
        setSourceLoading(false);
      }
    }
  }

  function rememberDraft(nextSource = source, nextNotes = notes) {
    if (editorTarget) {
      sessionStorage.setItem(
        draftKey(editorTarget.task.id),
        JSON.stringify({ source: nextSource, notes: nextNotes }),
      );
    }
  }

  async function createTask(values: {
    name: string;
    description?: string;
    category?: string;
    capabilities?: string;
  }) {
    setCreateBusy(true);
    try {
      const task = await apiRequest<Task>(session, "/tasks", {
        method: "POST",
        body: JSON.stringify({
          name: values.name.trim(),
          description: values.description?.trim() ?? "",
          category: values.category?.trim() || "自动化任务",
          capabilities: values.capabilities?.split(",").map((item) => item.trim()).filter(Boolean) ?? [],
        }),
      });
      setShowCreate(false);
      createForm.resetFields();
      await reload();
      await openEditor(task, true);
    } catch (error) {
      notice.error(error instanceof Error ? error.message : "任务创建失败");
    } finally {
      setCreateBusy(false);
    }
  }

  function confirmPublish() {
    if (!editorTarget || !source.trim()) {
      notice.error("请先编写 Python 脚本");
      return;
    }
    Modal.confirm({
      title: `发布 ${version}？`,
      content: "发布后此不可变版本将提供给获授权的设备，并可能由受控端自动运行。请先完成代码审查并确认能力声明。",
      okText: "确认发布",
      okButtonProps: { danger: true },
      cancelText: "返回编辑",
      onOk: async () => {
        setPublishing(true);
        try {
          await publishScriptVersion(session, editorTarget.task.id, {
            version,
            filename,
            notes,
            source,
          });
          sessionStorage.removeItem(draftKey(editorTarget.task.id));
          notice.success(`版本 ${version} 已校验并发布`);
          setEditorTarget(null);
          await reload();
        } catch (error) {
          notice.error(error instanceof Error ? error.message : "版本发布失败");
          throw error;
        } finally {
          setPublishing(false);
        }
      },
    });
  }

  function setTaskStatus(task: Task, status: "published" | "paused") {
    const title = status === "paused" ? "暂停此任务？" : "恢复此任务？";
    Modal.confirm({
      title,
      content: status === "paused"
        ? "新设备同步和运行将被拒绝，未处理请求会过期。已开始的运行不会被强制中止。"
        : "恢复后，符合授权条件的设备可重新同步并运行当前版本。",
      okText: status === "paused" ? "暂停任务" : "恢复任务",
      okButtonProps: status === "paused" ? { danger: true } : undefined,
      cancelText: "取消",
      onOk: async () => {
        await apiRequest(session, `/tasks/${encodeURIComponent(task.id)}`, {
          method: "PATCH",
          body: JSON.stringify({ status }),
        });
        await reload();
      },
    });
  }

  async function activateVersion(task: Task, selectedVersion: string) {
    Modal.confirm({
      title: `切换当前版本为 ${selectedVersion}？`,
      content: "历史版本内容不会被修改；后续新运行请求将使用此版本。",
      okText: "切换版本",
      cancelText: "取消",
      onOk: async () => {
        await apiRequest(
          session,
          `/tasks/${encodeURIComponent(task.id)}/versions/${encodeURIComponent(selectedVersion)}/activate`,
          { method: "PATCH" },
        );
        notice.success("当前版本已切换");
        setVersionsTask(null);
        await reload();
      },
    });
  }

  async function compareVersionSources(task: Task, older: string, newer: string) {
    setCompareVersions({ older, newer });
    setCompareSources(null);
    try {
      const [original, modified] = await Promise.all([
        requestScriptSource(session, task.id, older),
        requestScriptSource(session, task.id, newer),
      ]);
      setCompareSources({ original, modified });
    } catch (error) {
      notice.error(error instanceof Error ? error.message : "版本源码读取失败");
    }
  }

  return (
    <div className="page-stack">
      {contextHolder}
      <div className="page-heading">
        <div>
          <Typography.Title level={2}>自动化任务</Typography.Title>
          <Typography.Paragraph type="secondary">
            编写 Python 脚本，校验版本并向获授权的设备发布
          </Typography.Paragraph>
        </div>
        <Button type="primary" icon={<FileAddOutlined />} onClick={() => setShowCreate(true)}>
          新建任务
        </Button>
      </div>
      <Alert
        type="warning"
        showIcon
        message="脚本会在受控设备上运行"
        description="服务端不执行 Python。发布前请审查源码和能力声明；设备仍须遵循 Android 系统权限及可见运行要求。"
      />
      <Card bordered={false}>
        <div className="table-toolbar">
          <Input.Search
            allowClear
            value={filter}
            onChange={(event) => setFilter(event.target.value)}
            placeholder="搜索任务名称、说明、分类、状态或版本"
            style={{ maxWidth: 420 }}
          />
        </div>
        <Table<Task>
          rowKey="id"
          loading={loading}
          columns={columns}
          dataSource={filteredTasks}
          pagination={{ pageSize: 10, showSizeChanger: true, showTotal: (total) => `共 ${total} 个任务` }}
          locale={{ emptyText: "还没有自动化任务，创建任务并编写第一个脚本版本。" }}
        />
      </Card>

      <Modal
        title="创建自动化任务"
        open={showCreate}
        onCancel={() => setShowCreate(false)}
        onOk={() => createForm.submit()}
        okText="创建并编写脚本"
        cancelText="取消"
        confirmLoading={createBusy}
        destroyOnClose
      >
        <Form
          form={createForm}
          layout="vertical"
          onFinish={(values) => void createTask(values)}
          initialValues={{ category: "自动化任务", capabilities: "" }}
        >
          <Form.Item name="name" label="任务名称" rules={[{ required: true, max: 120 }]}>
            <Input maxLength={120} placeholder="例如：订单状态检查" />
          </Form.Item>
          <Form.Item name="description" label="说明">
            <Input.TextArea maxLength={500} showCount rows={3} />
          </Form.Item>
          <Form.Item name="category" label="分类">
            <Input maxLength={60} />
          </Form.Item>
          <Form.Item name="capabilities" label="能力声明" extra="多个能力用逗号分隔">
            <Input placeholder="OCR 文字识别, 屏幕捕获" />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={editorTarget ? `脚本编辑器 · ${editorTarget.task.name}` : ""}
        open={Boolean(editorTarget)}
        onCancel={() => setEditorTarget(null)}
        footer={null}
        width={1120}
        destroyOnClose
        className="script-editor-modal"
      >
        {editorTarget && (
          <div className="editor-layout">
            <Alert
              type="warning"
              showIcon
              message="发布会使版本对已授权用户/设备可用；设备端可能自动运行"
              description="编辑过程自动保存在当前浏览器标签页。发布不可撤回或覆盖；暂停任务可阻止后续下载与运行。"
            />
            <Space wrap className="editor-toolbar">
              <Input
                aria-label="版本号"
                value={version}
                onChange={(event) => setVersion(event.target.value)}
                addonBefore="版本"
                placeholder="v1.0.0"
              />
              <Input
                aria-label="脚本文件名"
                value={filename}
                onChange={(event) => setFilename(event.target.value)}
                addonBefore="文件"
                placeholder="main.py"
              />
              <Button
                icon={<SaveOutlined />}
                onClick={() => {
                  rememberDraft();
                  notice.success("草稿已保存在当前浏览器标签页");
                }}
              >
                保存草稿
              </Button>
              <Button type="primary" danger loading={publishing} onClick={confirmPublish}>
                校验并发布新版本
              </Button>
            </Space>
            <Input
              aria-label="版本说明"
              value={notes}
              onChange={(event) => {
                setNotes(event.target.value);
                rememberDraft(source, event.target.value);
              }}
              maxLength={500}
              placeholder="版本更新说明"
            />
            <div className="monaco-shell" aria-label="Python 脚本编辑器">
              <Editor
                  height="min(62vh, 680px)"
                  language="python"
                  theme="vs-dark"
                  value={source}
                  loading="正在加载脚本源码..."
                  options={{
                    automaticLayout: true,
                    fontSize: 14,
                    minimap: { enabled: false },
                    scrollBeyondLastLine: false,
                    tabSize: 4,
                    insertSpaces: true,
                    wordWrap: "on",
                    accessibilitySupport: "on",
                  }}
                  onChange={(value) => {
                    const nextSource = value ?? "";
                    setSource(nextSource);
                    rememberDraft(nextSource, notes);
                  }}
                  onMount={(editor, monaco) => {
                    editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.KeyS, () => {
                      rememberDraft(editor.getValue(), notes);
                      notice.success("草稿已保存在当前浏览器标签页；发布需单独确认");
                    });
                  }}
                />
              {sourceLoading && <div className="editor-loading">正在读取当前版本...</div>}
            </div>
            <Descriptions size="small" column={2}>
              <Descriptions.Item label="当前线上版本">
                {editorTarget.task.active_version || "尚无已发布版本"}
              </Descriptions.Item>
              <Descriptions.Item label="哈希校验">发布时计算 SHA-256，服务端复核</Descriptions.Item>
            </Descriptions>
          </div>
        )}
      </Modal>

      <Modal
        title={versionsTask ? `版本历史 · ${versionsTask.name}` : ""}
        open={Boolean(versionsTask)}
        onCancel={() => setVersionsTask(null)}
        footer={<Button onClick={() => setVersionsTask(null)}>关闭</Button>}
      >
        {versionsTask && (
          <Table<Task["versions"][number]>
            rowKey="version"
            pagination={false}
            dataSource={versionsTask.versions}
            locale={{ emptyText: "暂无已发布版本" }}
            columns={[
              {
                title: "版本",
                dataIndex: "version",
                render: (value: string) => (
                  <Space>
                    {value}
                    {value === versionsTask.active_version && <Tag color="green">当前版本</Tag>}
                  </Space>
                ),
              },
              { title: "文件", dataIndex: "filename" },
              { title: "SHA-256", dataIndex: "sha256", render: (value: string) => <Typography.Text code copyable>{value.slice(0, 16)}…</Typography.Text> },
              {
                title: "发布时间",
                dataIndex: "created_at",
                render: (value: string) => value ? new Date(value).toLocaleString() : "—",
              },
              {
                title: "操作",
                render: (_: unknown, item) => (
                  <Space>
                    {versionsTask.versions.length > 1 && item.version !== versionsTask.active_version && (
                      <Button
                        type="link"
                        onClick={() => void compareVersionSources(
                          versionsTask,
                          item.version,
                          versionsTask.active_version || versionsTask.versions[0].version,
                        )}
                      >
                        对比当前
                      </Button>
                    )}
                    {item.version === versionsTask.active_version
                      ? "—"
                      : <Button type="link" onClick={() => activateVersion(versionsTask, item.version)}>设为当前</Button>}
                  </Space>
                ),
              },
            ]}
          />
        )}
      </Modal>

      <Modal
        title={compareVersions ? `版本差异 · ${compareVersions.older} → ${compareVersions.newer}` : "版本差异"}
        open={Boolean(compareVersions)}
        onCancel={() => {
          setCompareVersions(null);
          setCompareSources(null);
        }}
        footer={<Button onClick={() => {
          setCompareVersions(null);
          setCompareSources(null);
        }}>关闭</Button>}
        width={1200}
        destroyOnClose
      >
        {compareSources ? (
          <DiffEditor
              height="min(65vh, 720px)"
              language="python"
              theme="vs-dark"
              original={compareSources.original}
              modified={compareSources.modified}
              options={{
                readOnly: true,
                renderSideBySide: true,
                minimap: { enabled: false },
                automaticLayout: true,
                scrollBeyondLastLine: false,
              }}
            />
        ) : <div className="editor-placeholder">正在读取版本源码...</div>}
      </Modal>
    </div>
  );
}
