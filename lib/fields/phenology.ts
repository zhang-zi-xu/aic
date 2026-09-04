// 生育期推算：按作物、播期估算当前所处生育阶段。
// 简化模型（按天数窗口），正式版可替换为积温模型；输出标注「估算」。

export type PhenologyPhase = {
  name: string;
  dayFrom: number; // 自播期起天数（含）
  dayTo: number;   // 自播期起天数（不含）
  notes: string;
};

type CropPhenology = {
  phases: PhenologyPhase[];
};

// 常规稻麦玉生育期窗口（南北差异显著，仅用于估算与提醒）
const TABLES: Record<string, CropPhenology> = {
  '水稻': {
    phases: [
      { name: '秧苗期', dayFrom: 0, dayTo: 25, notes: '播种至移栽，注意育秧温度与秧龄' },
      { name: '返青分蘖期', dayFrom: 25, dayTo: 55, notes: '够苗晒田，控蘖防倒' },
      { name: '拔节孕穗期', dayFrom: 55, dayTo: 85, notes: '病虫害窗口，穗颈瘟预防关键期' },
      { name: '抽穗扬花期', dayFrom: 85, dayTo: 100, notes: '浅水保穗，关注稻飞虱与螟虫' },
      { name: '灌浆成熟期', dayFrom: 100, dayTo: 130, notes: '干湿交替，收割前 7 天断水' },
    ],
  },
  '小麦': {
    phases: [
      { name: '出苗分蘖期', dayFrom: 0, dayTo: 60, notes: '冬前壮苗为核心' },
      { name: '返青拔节期', dayFrom: 60, dayTo: 120, notes: '看苗追肥，预防倒春寒' },
      { name: '孕穗抽穗期', dayFrom: 120, dayTo: 150, notes: '赤霉病「见花打药」关键窗口' },
      { name: '灌浆成熟期', dayFrom: 150, dayTo: 200, notes: '干热风风险，麦黄水预防' },
    ],
  },
  '玉米': {
    phases: [
      { name: '苗期（出苗—拔节）', dayFrom: 0, dayTo: 30, notes: '查苗补苗，控旺化控窗口 6-8 叶' },
      { name: '拔节孕穗期（小喇叭口）', dayFrom: 30, dayTo: 50, notes: '大喇叭口期玉米螟防治' },
      { name: '抽雄吐丝期', dayFrom: 50, dayTo: 65, notes: '水肥关键期，防卡脖旱' },
      { name: '灌浆成熟期', dayFrom: 65, dayTo: 100, notes: '防倒伏、防锈病，适期收获' },
    ],
  },
};

export function getPhenology(crop: string, sowingDate: string, today?: Date): { phase: PhenologyPhase | null; days: number; note: string } {
  const table = TABLES[crop];
  if (!table) return { phase: null, days: 0, note: '' };
  const sowing = new Date(sowingDate);
  if (Number.isNaN(sowing.getTime())) return { phase: null, days: 0, note: '' };
  const now = today ?? new Date();
  const days = Math.floor((now.getTime() - sowing.getTime()) / 86400000);
  if (days < 0) {
    return { phase: null, days, note: '播期尚未到，请确认日期；暂不推算生育期' };
  }
  const phase = table.phases.find((p) => days >= p.dayFrom && days < p.dayTo) ?? null;
  return {
    phase,
    days,
    note: phase ? `${phase.name}（估）${phase.notes}` : `已播 ${days} 天，超出简化模型范围，请确认实际生育期`,
  };
}
