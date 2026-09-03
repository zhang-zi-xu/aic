import type { Metadata } from 'next';
import './globals.css';

export const metadata: Metadata = {
  title: '农心 Agent｜田间决策助手',
  description: '让农技知识真正落到每一块田里的农业智能体。',
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return <html lang="zh-CN"><body>{children}</body></html>;
}
