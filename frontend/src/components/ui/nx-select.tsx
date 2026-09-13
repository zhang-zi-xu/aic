'use client';

import type { ReactNode } from 'react';
import { Select } from '@base-ui/react/select';
import { Check, ChevronDown } from 'lucide-react';

export type NxSelectOption = {
  value: string;
  label: string;
  /** 右侧次要信息，如作物、日期、备注 */
  hint?: string;
  disabled?: boolean;
};

/**
 * 统一的下拉选择器：原生 <select> 展开后的系统菜单无法定制，
 * 这里用 Base UI Select 自绘弹层，键盘、焦点与无障碍行为由组件库保证。
 */
export function NxSelect({ value, onValueChange, options, ariaLabel, disabled, placeholder = '请选择', leading, className = '', footer, emptyText = '暂无可选项' }: {
  value: string;
  onValueChange: (value: string) => void;
  options: NxSelectOption[];
  ariaLabel: string;
  disabled?: boolean;
  placeholder?: ReactNode;
  /** 触发器左侧图标 */
  leading?: ReactNode;
  className?: string;
  /** 弹层底部附加操作 */
  footer?: ReactNode;
  emptyText?: string;
}) {
  return <Select.Root
    value={value}
    onValueChange={(next: string | null) => onValueChange(next ?? '')}
    disabled={disabled}
    items={options.map(option => ({ value: option.value, label: option.label }))}
    itemToStringLabel={(itemValue: string) => options.find(option => option.value === itemValue)?.label ?? ''}>
    <Select.Trigger className={`nx-select-trigger ${className}`} aria-label={ariaLabel}>
      {leading && <span className="nx-select-leading">{leading}</span>}
      <Select.Value className="nx-select-value">
        {(selected: string) => {
          const current = options.find(option => option.value === selected);
          if (!current) return <span className="nx-select-placeholder">{placeholder}</span>;
          return <><span className="nx-select-label">{current.label}</span>{current.hint && <em className="nx-select-hint">{current.hint}</em>}</>;
        }}
      </Select.Value>
      <Select.Icon className="nx-select-icon"><ChevronDown size={14} /></Select.Icon>
    </Select.Trigger>
    <Select.Portal>
      <Select.Positioner className="nx-select-positioner" sideOffset={6} align="start" alignItemWithTrigger={false}>
        <Select.Popup className="nx-select-popup">
          {options.length === 0 && <p className="nx-select-empty">{emptyText}</p>}
          {options.map(option => <Select.Item key={option.value || '__none__'} value={option.value} label={option.label}
            disabled={option.disabled} className="nx-select-item">
            <Select.ItemIndicator className="nx-select-indicator"><Check size={14} /></Select.ItemIndicator>
            <Select.ItemText className="nx-select-item-text">{option.label}</Select.ItemText>
            {option.hint && <span className="nx-select-item-hint">{option.hint}</span>}
          </Select.Item>)}
          {footer && <div className="nx-select-footer">{footer}</div>}
        </Select.Popup>
      </Select.Positioner>
    </Select.Portal>
  </Select.Root>;
}
