import "@ant-design/v5-patch-for-react-19";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { Button, Modal } from "antd";
import { afterEach, describe, expect, it, vi } from "vitest";

const getComputedStyle = window.getComputedStyle.bind(window);

afterEach(() => vi.restoreAllMocks());

function ConfirmationTrigger() {
  return (
    <Button
      onClick={() => Modal.confirm({
        title: "发布确认",
        content: "确认发布这个版本？",
        okText: "确认发布",
      })}
    >
      打开确认
    </Button>
  );
}

describe("Ant Design React 19 compatibility", () => {
  it("renders and closes static confirmation dialogs", async () => {
    vi.spyOn(window, "getComputedStyle").mockImplementation((element, pseudoElement) =>
      getComputedStyle(element, pseudoElement ? undefined : pseudoElement),
    );
    render(<ConfirmationTrigger />);
    fireEvent.click(screen.getByRole("button", { name: "打开确认" }));

    expect(await screen.findByRole("dialog")).toHaveTextContent("确认发布这个版本？");
    fireEvent.click(screen.getByRole("button", { name: "确认发布" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
  });
});
