import { useEffect, useMemo, useRef, useState } from "react";
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
  Select,
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
  deleteScriptDraft,
  getRemoteRun,
  getScriptDraft,
  publishScriptVersion,
  requestScriptSource,
  saveScriptDraft,
  startRemoteRun,
  stopRemoteRun,
  type Device,
  type RemoteRun,
  type Session,
  type Task,
} from "./api";
import { canManageTask, matchesTaskSearch, nextPatchVersion } from "./policies";

type Props = { session: Session };
type EditorTarget = { task: Task; isNewTask: boolean } | null;

const taskStatus: Record<Task["status"], { label: string; color: string }> = {
  draft: { label: "草稿", color: "default" },
  published: { label: "已发布", color: "green" },
  paused: { label: "已暂停", color: "orange" },
};

function runStatusLabel(status: string): string {
  const labels: Record<string, string> = {
    pending: "等待设备",
    queued: "排队中",
    running: "运行中",
    success: "运行成功",
    failure: "运行失败",
    stopped: "已停止",
    expired: "请求已过期",
    rejected: "设备拒绝",
  };
  return labels[status] ?? status;
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
  const [draftLoaded, setDraftLoaded] = useState(false);
  const [draftStatus, setDraftStatus] = useState<"saved" | "saving" | "error" | "conflict">("saved");
  const [draftRevision, setDraftRevision] = useState(0);
  const [draftError, setDraftError] = useState("");
  const [runTaskTarget, setRunTaskTarget] = useState<Task | null>(null);
  const [devices, setDevices] = useState<Device[]>([]);
  const [selectedDeviceId, setSelectedDeviceId] = useState<string>();
  const [loadingDevices, setLoadingDevices] = useState(false);
  const [activeRemoteRun, setActiveRemoteRun] = useState<RemoteRun | null>(null);
  const draftRevisionRef = useRef(0);
  const draftQueueRef = useRef(Promise.resolve());
  const lastSavedDraftRef = useRef("");
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
  const draftSignature = JSON.stringify({ source, filename, notes });
  async function persistDraft(taskId: string, snapshot: { source: string; filename: string; notes: string }) {
    const signature = JSON.stringify(snapshot);
    if (lastSavedDraftRef.current === signature) return draftRevisionRef.current;
    setDraftStatus("saving");
    const operation = draftQueueRef.current
      .catch(() => undefined)
      .then(() => saveScriptDraft(session, taskId, {
        ...snapshot,
        expected_revision: draftRevisionRef.current,
      }));
    draftQueueRef.current = operation.then(() => undefined, () => undefined);
    try {
      const result = await operation;
      draftRevisionRef.current = result.revision;
      lastSavedDraftRef.current = signature;
      if (editorTarget?.task.id === taskId) {
        setDraftRevision(result.revision);
        setDraftStatus("saved");
        setDraftError("");
      }
      return result.revision;
    } catch (error) {
      if (editorTarget?.task.id === taskId) {
        setDraftStatus(error instanceof Error && "status" in error && error.status === 409 ? "conflict" : "error");
        setDraftError(error instanceof Error ? error.message : "草稿保存失败");
      }
      throw error;
    }
  }

  useEffect(() => {
    if (!editorTarget || !draftLoaded || draftSignature === lastSavedDraftRef.current) return;
    const taskId = editorTarget.task.id;
    const snapshot = { source, filename, notes };
    const timer = window.setTimeout(() => {
      void persistDraft(taskId, snapshot).catch(() => undefined);
    }, 700);
    return () => window.clearTimeout(timer);
  }, [draftLoaded, draftSignature, editorTarget?.task.id, filename, notes, session.token, source]);

  useEffect(() => {
    if (!activeRemoteRun) return;
    let active = true;
    let timer = 0;
    const poll = async () => {
      try {
        const result = await getRemoteRun(session, activeRemoteRun.id);
        if (!active) return;
        setActiveRemoteRun(result);
        if (["success", "failure", "stopped", "expired", "rejected"].includes(result.status)) {
          notice.open({
            type: result.status === "success" ? "success" : result.status === "running" ? "info" : "warning",
            content: `任务状态：${runStatusLabel(result.status)}`,
          });
          void reload();
        } else {
          timer = window.setTimeout(() => void poll(), 2000);
        }
      } catch (error) {
        if (active) {
          notice.error(error instanceof Error ? error.message : "运行状态查询失败");
          timer = window.setTimeout(() => void poll(), 4000);
        }
      }
    };
    timer = window.setTimeout(() => void poll(), 1500);
    return () => {
      active = false;
      window.clearTimeout(timer);
    };
  }, [activeRemoteRun?.id, activeRemoteRun?.status, session.token]);

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
          {canManageTask(task, session.user) && <Button icon={<CodeOutlined />} onClick={() => void openEditor(task)}>编写脚本</Button>}
          <Button icon={<HistoryOutlined />} onClick={() => setVersionsTask(task)}>版本历史</Button>
          {task.status === "paused" ? (
            canManageTask(task, session.user) && <Button
                type="link"
                icon={<PlayCircleOutlined />}
                onClick={() => void setTaskStatus(task, "published")}
              >
                恢复
              </Button>
          ) : (
            canManageTask(task, session.user) && <Button
                type="link"
                danger
                icon={<PauseCircleOutlined />}
                onClick={() => void setTaskStatus(task, "paused")}
              >
                暂停
              </Button>
          )}
          {session.user.role === "user" && task.status === "published" && task.active_version && (
            <Button type="primary" onClick={() => void prepareRun(task)}>运行</Button>
          )}
        </Space>
      ),
    },
  ], [session.token, tasks]);

  async function openEditor(task: Task, isNewTask = false) {
    setEditorTarget({ task, isNewTask });
    setDraftLoaded(false);
    setDraftStatus("saved");
    setDraftError("");
    setSource("");
    setNotes("");
    setVersion(nextPatchVersion(task.active_version, task.versions.map((item) => item.version)));
    setFilename(task.versions?.[0]?.filename ?? "main.py");
    setSourceLoading(true);
    try {
      const draft = await getScriptDraft(session, task.id);
      let editorSource = draft.source;
      let editorFilename = draft.filename;
      let editorNotes = draft.notes;
      if (!draft.saved && !isNewTask && task.active_version) {
        const original = await requestScriptSource(session, task.id, task.active_version);
        editorSource = original;
        editorFilename = task.versions.find((item) => item.version === task.active_version)?.filename ?? "main.py";
      }
      setSource(editorSource);
      setFilename(editorFilename);
      setNotes(editorNotes);
      draftRevisionRef.current = draft.revision;
      setDraftRevision(draft.revision);
      lastSavedDraftRef.current = JSON.stringify({
        source: editorSource,
        filename: editorFilename,
        notes: editorNotes,
      });
      setDraftLoaded(true);
    } catch (error) {
      setDraftStatus("error");
      setDraftError(error instanceof Error ? error.message : "草稿读取失败");
      notice.error(error instanceof Error ? error.message : "无法读取脚本草稿");
    } finally {
      setSourceLoading(false);
    }
  }

  async function reloadDraft() {
    if (!editorTarget) return;
    Modal.confirm({
      title: "重新加载服务端草稿？",
      content: "当前编辑器中尚未保存的内容会被服务端版本替换。",
      okText: "重新加载",
      cancelText: "继续编辑",
      onOk: () => openEditor(editorTarget.task, editorTarget.isNewTask),
    });
  }

  async function prepareRun(task: Task) {
    setRunTaskTarget(task);
    setSelectedDeviceId(undefined);
    setActiveRemoteRun(null);
    setLoadingDevices(true);
    try {
      const available = await apiRequest<Device[]>(session, "/devices");
      setDevices(available.filter((device) => device.status === "online"));
    } catch (error) {
      notice.error(error instanceof Error ? error.message : "设备列表加载失败");
    } finally {
      setLoadingDevices(false);
    }
  }

  async function submitRun() {
    if (!runTaskTarget || !selectedDeviceId) return;
    try {
      const request = await startRemoteRun(session, runTaskTarget.id, selectedDeviceId);
      setActiveRemoteRun(request);
      notice.success("运行请求已提交");
    } catch (error) {
      notice.error(error instanceof Error ? error.message : "运行请求提交失败");
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
      const savedRevision = await persistDraft(editorTarget.task.id, { source, filename, notes });
      await publishScriptVersion(session, editorTarget.task.id, {
            version,
            filename,
            notes,
            source,
          });
          try {
            await deleteScriptDraft(session, editorTarget.task.id, savedRevision);
            draftRevisionRef.current = savedRevision + 1;
          } catch (error) {
            notice.warning(`版本已发布，但草稿有并发更新，已保留草稿：${error instanceof Error ? error.message : "请重新检查草稿"}`);
          }
          notice.success(`版本 ${version} 已校验并发布`);
          setEditorTarget(null);
          setDraftLoaded(false);
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
        title={runTaskTarget ? `运行任务 · ${runTaskTarget.name}` : "运行任务"}
        open={Boolean(runTaskTarget)}
        onCancel={() => setRunTaskTarget(null)}
        footer={activeRemoteRun ? (
          <Space>
            {activeRemoteRun.status === "running" && (
              <Button danger onClick={() => void stopRemoteRun(session, activeRemoteRun.id)
                .then((result) => setActiveRemoteRun({ ...activeRemoteRun, ...result }))
                .catch((error: unknown) => notice.error(error instanceof Error ? error.message : "停止请求失败"))}>
                请求停止
              </Button>
            )}
            <Button onClick={() => setRunTaskTarget(null)}>关闭</Button>
          </Space>
        ) : (
          <Space>
            <Button onClick={() => setRunTaskTarget(null)}>取消</Button>
            <Button type="primary" disabled={!selectedDeviceId || devices.length === 0} onClick={() => void submitRun()}>
              开始运行
            </Button>
          </Space>
        )}
        width="min(560px, calc(100vw - 24px))"
        destroyOnClose
      >
        {activeRemoteRun ? (
          <div className="run-progress">
            <Typography.Title level={4}>{runStatusLabel(activeRemoteRun.status)}</Typography.Title>
            <Typography.Paragraph>
              {activeRemoteRun.result_summary || (
                activeRemoteRun.status === "running" ? "任务正在受控设备上运行。" : "等待设备接收并开始执行。关闭此窗口不会取消运行请求。"
              )}
            </Typography.Paragraph>
            {activeRemoteRun.status === "running" && <Typography.Text type="secondary">停止请求会在脚本下一个停止检查点生效。</Typography.Text>}
          </div>
        ) : (
          <Form layout="vertical">
            <Form.Item label="目标受控设备" required>
              <Select
                value={selectedDeviceId}
                onChange={setSelectedDeviceId}
                loading={loadingDevices}
                placeholder={loadingDevices ? "正在读取设备..." : "选择在线设备"}
                options={devices.map((device) => ({
                  value: device.id,
                  label: `${device.name} · ${device.model}`,
                }))}
                notFoundContent={loadingDevices ? "正在加载" : "没有在线设备"}
              />
            </Form.Item>
            <Alert
              type="warning"
              showIcon
              message="运行将在受控设备上执行"
              description="发布脚本不会自动运行。运行期间设备遵循 Android 系统权限及可见通知要求。"
            />
          </Form>
        )}
      </Modal>

      <Modal
        title={editorTarget ? `脚本编辑器 · ${editorTarget.task.name}` : ""}
        open={Boolean(editorTarget)}
        onCancel={() => {
          setEditorTarget(null);
          setDraftLoaded(false);
        }}
        footer={null}
        width="min(1120px, calc(100vw - 24px))"
        destroyOnClose
        className="script-editor-modal"
      >
        {editorTarget && (
          <div className="editor-layout">
            <Alert
              type="warning"
              showIcon
              message="发布会使版本对已授权用户/设备可用；设备端可能自动运行"
              description="编辑内容会自动保存到账号的服务端草稿，可在其他会话恢复。发布生成不可变版本；请确认源码和能力声明。"
            />
            {draftStatus === "conflict" && (
              <Alert
                type="error"
                showIcon
                message="草稿版本冲突"
                description={draftError}
                action={<Button size="small" onClick={() => void reloadDraft()}>重新加载服务端草稿</Button>}
              />
            )}
            {draftStatus === "error" && (
              <Alert
                type="error"
                showIcon
                message="草稿未保存"
                description={draftError}
                action={<Button size="small" onClick={() => {
                  if (editorTarget) void persistDraft(editorTarget.task.id, { source, filename, notes })
                    .then(() => notice.success("草稿已保存"))
                    .catch((error: unknown) => notice.error(error instanceof Error ? error.message : "草稿保存失败"));
                }}>重试保存</Button>}
              />
            )}
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
                loading={draftStatus === "saving"}
                onClick={() => editorTarget && void persistDraft(
                  editorTarget.task.id,
                  { source, filename, notes },
                ).then(() => notice.success("草稿已保存")).catch((error: unknown) => notice.error(
                  error instanceof Error ? error.message : "草稿保存失败",
                ))}
              >
                {draftStatus === "saving" ? "正在保存" : "保存草稿"}
              </Button>
              <Typography.Text type={draftStatus === "saved" ? "success" : "secondary"}>
                {draftStatus === "saved" ? `已保存 · 修订 ${draftRevision}` : draftStatus === "conflict" ? "存在并发修改" : draftStatus === "error" ? "保存失败" : "等待保存"}
              </Typography.Text>
              <Button type="primary" danger loading={publishing} onClick={confirmPublish}>
                校验并发布新版本
              </Button>
            </Space>
            <Input
              aria-label="版本说明"
              value={notes}
              onChange={(event) => {
                setNotes(event.target.value);
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
                  onChange={(value) => setSource(value ?? "")}
                  onMount={(editor, monaco) => {
                    editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.KeyS, () => {
                      if (editorTarget) void persistDraft(
                        editorTarget.task.id,
                        { source: editor.getValue(), filename, notes },
                      ).then(() => notice.success("草稿已保存；发布需单独确认")).catch((error: unknown) => notice.error(
                        error instanceof Error ? error.message : "草稿保存失败",
                      ));
                    });
                  }}
                />
              {sourceLoading && <div className="editor-loading">正在读取当前版本...</div>}
            </div>
            <Descriptions size="small" column={2}>
              <Descriptions.Item label="当前发布版本">
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
                      : canManageTask(versionsTask, session.user) && <Button type="link" onClick={() => activateVersion(versionsTask, item.version)}>设为当前</Button>}
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
        width="min(1200px, calc(100vw - 24px))"
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
                renderSideBySide: window.innerWidth > 768,
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
