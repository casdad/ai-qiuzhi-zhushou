"use client"

import { useEffect, useRef } from "react"
import {
  Chart as ChartJS,
  BarController,
  LineController,
  PieController,
  CategoryScale,
  LinearScale,
  ArcElement,
  BarElement,
  LineElement,
  PointElement,
  Tooltip,
  Legend,
  type ChartOptions,
} from "chart.js"

import { CATEGORY_COLORS } from "@/lib/analytics"

// 按需注册：只注册本页用到的控制器、比例尺、图元与插件，避免把整个 Chart.js 打进包里。
// 控制器必须成套注册，漏掉 BarController/PieController/LineController 会在
// new Chart() 时直接抛 '"pie" is not a registered controller'，六张图全空白。
ChartJS.register(
  BarController,
  LineController,
  PieController,
  CategoryScale,
  LinearScale,
  ArcElement,
  BarElement,
  LineElement,
  PointElement,
  Tooltip,
  Legend
)

export type ChartKind = "bar" | "line" | "pie"

/**
 * 图表画布。
 *
 * 四个分析页此前各抄了一份 140 行的 ChartCanvas，包括同一个
 * "动态插入 chart.js CDN <script>" 的加载器。项目本身就是本地离线运行的应用，
 * 断网或 CDN 被墙时六张图会一起变成空白 —— 而 chart.js 早已在 package.json 依赖里，
 * 只是没人 import。这里改成直接用打包好的库，同时消除了四份重复代码。
 */
export function ChartCanvas({
  kind,
  labels,
  data,
  colors,
  color = CATEGORY_COLORS[0],
  height = 256,
}: {
  kind: ChartKind
  labels: string[]
  data: number[]
  /** 分类配色；不传时饼图用调色盘，柱/线图用单色。 */
  colors?: readonly string[]
  color?: string
  height?: number
}) {
  const canvasRef = useRef<HTMLCanvasElement | null>(null)

  useEffect(() => {
    const canvas = canvasRef.current
    if (!canvas) return
    const ctx = canvas.getContext("2d")
    if (!ctx) return

    const chart = new ChartJS(ctx, {
      type: kind,
      data: {
        labels,
        datasets: [
          {
            label: "岗位数",
            data,
            backgroundColor:
              kind === "pie"
                ? Array.from({ length: labels.length }, (_, i) => colors?.[i % colors.length] ?? CATEGORY_COLORS[i % CATEGORY_COLORS.length])
                : kind === "bar" && colors
                  ? Array.from({ length: data.length }, (_, i) => colors[i % colors.length])
                  : color,
            borderColor: kind === "pie" ? "#ffffff" : color,
            borderWidth: kind === "pie" ? 2 : 0,
            borderRadius: kind === "bar" ? 4 : 0,
            tension: 0.3,
            pointRadius: kind === "line" ? 3 : undefined,
          },
        ],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        plugins: {
          legend: {
            display: kind === "pie",
            position: "bottom",
            labels: { boxWidth: 10, boxHeight: 10, font: { size: 11 } },
          },
        },
        scales:
          kind === "pie"
            ? undefined
            : {
                x: {
                  grid: { display: false },
                  ticks: {
                    autoSkip: true,
                    maxRotation: 40,
                    minRotation: 0,
                    font: { size: 11 },
                  },
                },
                y: { beginAtZero: true, grid: { color: "rgba(148,163,184,0.16)" }, ticks: { precision: 0 } },
              },
      } satisfies ChartOptions<ChartKind>,
    })

    return () => chart.destroy()
  }, [kind, labels, data, colors, color])

  return <canvas ref={canvasRef} style={{ height }} className="w-full" />
}