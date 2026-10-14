import * as monaco from "monaco-editor/esm/vs/editor/editor.api";
import "monaco-editor/esm/vs/basic-languages/python/python.contribution";
import EditorWorker from "monaco-editor/esm/vs/editor/editor.worker?worker";
import { loader } from "@monaco-editor/react";

window.MonacoEnvironment = {
  getWorker: () => new EditorWorker(),
};

loader.config({ monaco });
