import { useState } from "react";
import { Button, Checkbox, Form, Input, Typography, message } from "antd";
import { ApiError, login, type Session } from "./api";

type Props = { onLogin: (session: Session) => void };

export function Login({ onLogin }: Props) {
  const [busy, setBusy] = useState(false);
  const [notice, contextHolder] = message.useMessage();

  async function submit(values: { email: string; password: string; remember: boolean }) {
    setBusy(true);
    try {
      onLogin(await login(values.email.trim(), values.password, values.remember));
    } catch (error) {
      notice.error(error instanceof ApiError ? error.message : "登录失败，请检查服务连接后重试");
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="login-page">
      {contextHolder}
      <section className="login-card">
        <div className="brand-lockup">
          <span className="brand-symbol">F</span>
          <span>FlowPilot</span>
        </div>
        <Typography.Title level={2}>管理后台</Typography.Title>
        <Typography.Paragraph type="secondary">
          登录以管理用户、设备和自动化脚本
        </Typography.Paragraph>
        <Form layout="vertical" initialValues={{ remember: false }} onFinish={submit}>
          <Form.Item
            label="邮箱"
            name="email"
            rules={[{ required: true, type: "email", message: "请输入有效邮箱" }]}
          >
            <Input autoComplete="username" size="large" placeholder="name@company.com" />
          </Form.Item>
          <Form.Item
            label="密码"
            name="password"
            rules={[{ required: true, message: "请输入密码" }]}
          >
            <Input.Password autoComplete="current-password" size="large" />
          </Form.Item>
          <Form.Item name="remember" valuePropName="checked">
            <Checkbox>在此设备保持登录</Checkbox>
          </Form.Item>
          <Button type="primary" htmlType="submit" size="large" block loading={busy}>
            登录管理后台
          </Button>
        </Form>
        <Typography.Paragraph className="login-safety" type="secondary">
          生产后台仅接受服务端授权的管理员账号。请勿在共享设备上保持登录。
        </Typography.Paragraph>
      </section>
    </main>
  );
}
