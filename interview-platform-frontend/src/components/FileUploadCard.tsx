import {ChangeEvent, DragEvent, useCallback, useState} from 'react';
import {AnimatePresence, motion} from 'framer-motion';
import {AlertCircle, FileText, Loader2, Upload, X} from 'lucide-react';

type SelectOption = {
  label: string;
  value: string;
};

export interface FileUploadCardProps {
  /** 标题 */
  title: string;
  /** 副标题 */
  subtitle: string;
  /** 接受的文件类型 */
  accept: string;
  /** 支持的格式说明 */
  formatHint: string;
  /** 最大文件大小说明 */
  maxSizeHint: string;
  /** 是否正在上传 */
  uploading?: boolean;
  /** 上传按钮文字 */
  uploadButtonText?: string;
  /** 选择按钮文字 */
  selectButtonText?: string;
  /** 是否显示名称输入框 */
  showNameInput?: boolean;
  /** 名称输入框占位符 */
  namePlaceholder?: string;
  /** 名称输入框标签 */
  nameLabel?: string;
  /** 可选下拉配置（例如 tokenizer profile） */
  selectOptions?: SelectOption[];
  /** 下拉标签 */
  selectLabel?: string;
  /** 下拉值 */
  selectValue?: string;
  /** 下拉值变更 */
  onSelectValueChange?: (value: string) => void;
  /** 错误信息 */
  error?: string;
  /** 文件选择回调 */
  onFileSelect?: (file: File) => void;
  /** 上传回调 */
  onUpload: (file: File, name?: string, selectedOptionValue?: string) => void;
  /** 返回回调 */
  onBack?: () => void;
}

export default function FileUploadCard({
  title,
  subtitle,
  accept,
  formatHint,
  maxSizeHint,
  uploading = false,
  uploadButtonText = '开始上传',
  selectButtonText = '选择文件',
  showNameInput = false,
  namePlaceholder = '留空则使用文件名',
  nameLabel = '名称（可选）',
  selectOptions,
  selectLabel = '选项',
  selectValue,
  onSelectValueChange,
  error,
  onFileSelect,
  onUpload,
  onBack,
}: FileUploadCardProps) {
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [dragOver, setDragOver] = useState(false);
  const [name, setName] = useState('');

  const handleDragOver = useCallback((e: DragEvent) => {
    e.preventDefault();
    setDragOver(true);
  }, []);

  const handleDragLeave = useCallback((e: DragEvent) => {
    e.preventDefault();
    setDragOver(false);
  }, []);

  const handleDrop = useCallback((e: DragEvent) => {
    e.preventDefault();
    setDragOver(false);
    const files = e.dataTransfer.files;
    if (files.length > 0) {
      setSelectedFile(files[0]);
      onFileSelect?.(files[0]);
    }
  }, [onFileSelect]);

  const handleFileChange = useCallback((e: ChangeEvent<HTMLInputElement>) => {
    const files = e.target.files;
    if (files && files.length > 0) {
      setSelectedFile(files[0]);
      onFileSelect?.(files[0]);
    }
  }, [onFileSelect]);

  const handleUpload = () => {
    if (!selectedFile) return;
    onUpload(selectedFile, name.trim() || undefined, selectValue);
  };

  const formatFileSize = (bytes: number): string => {
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
  };

  return (
    <motion.div
      className="max-w-3xl mx-auto pt-16"
      initial={{ opacity: 0, y: 20 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.5 }}
    >
      {/* 标题 */}
      <div className="mb-12 text-center">
        <motion.p
          className="ui-kicker mb-3"
          initial={{ opacity: 0, y: 8 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ delay: 0.05 }}
        >
          从这里开始
        </motion.p>
        <motion.h1
          className="ui-page-title mb-4"
          initial={{ opacity: 0, y: 20 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ delay: 0.1 }}
        >
          {title}
        </motion.h1>
        <motion.p
          className="mx-auto max-w-xl text-lg text-stone-600 dark:text-stone-400"
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          transition={{ delay: 0.2 }}
        >
          {subtitle}
        </motion.p>
      </div>

      {/* 上传区域 */}
      <motion.div
        className={`ui-glow-ring relative cursor-pointer rounded-[1.75rem] border border-primary-200/45 bg-white/92 p-12 shadow-sm backdrop-blur-md transition-all duration-300 dark:border-primary-900/45 dark:bg-stone-900/82 ${
          dragOver ? 'scale-[1.01] ring-2 ring-primary-400/45 dark:ring-primary-500/35' : ''
        }`}
        onDragOver={handleDragOver}
        onDragLeave={handleDragLeave}
        onDrop={handleDrop}
        onClick={() => document.getElementById('file-upload-input')?.click()}
        initial={{ opacity: 0, y: 20 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ delay: 0.3 }}
      >
        <input
          type="file"
          id="file-upload-input"
          className="hidden"
          accept={accept}
          onChange={handleFileChange}
          disabled={uploading}
        />

        <div className="text-center">
          <AnimatePresence mode="wait">
            {selectedFile ? (
              <motion.div
                key="file-selected"
                initial={{ opacity: 0, scale: 0.9 }}
                animate={{ opacity: 1, scale: 1 }}
                exit={{ opacity: 0, scale: 0.9 }}
                className="space-y-4"
              >
                <div className="mx-auto flex h-20 w-20 items-center justify-center rounded-2xl bg-primary-100 dark:bg-primary-900/40">
                  <FileText className="h-10 w-10 text-primary-600 dark:text-primary-400" />
                </div>
                <div className="mx-auto flex max-w-md items-center justify-center gap-4 rounded-2xl border border-stone-200/80 bg-stone-50/90 px-6 py-4 dark:border-stone-600/60 dark:bg-stone-800/60">
                  <div className="min-w-0 flex-1 text-left">
                    <p className="truncate font-semibold text-stone-900 dark:text-white">{selectedFile.name}</p>
                    <p className="text-sm text-stone-500 dark:text-stone-400">{formatFileSize(selectedFile.size)}</p>
                  </div>
                  <button
                    className="flex h-8 w-8 items-center justify-center rounded-lg bg-red-100 text-red-600 transition-colors hover:bg-red-200 dark:bg-red-950/60 dark:text-red-400 dark:hover:bg-red-900/50"
                    onClick={(e) => {
                      e.stopPropagation();
                      setSelectedFile(null);
                    }}
                  >
                    <X className="w-4 h-4" />
                  </button>
                </div>
              </motion.div>
            ) : (
              <motion.div
                key="no-file"
                initial={{ opacity: 0 }}
                animate={{ opacity: 1 }}
                exit={{ opacity: 0 }}
                className="space-y-4"
              >
                <motion.div
                  className={`mx-auto flex h-20 w-20 items-center justify-center rounded-2xl transition-colors ${
                    dragOver
                      ? 'bg-primary-100 text-primary-700 dark:bg-primary-900/50 dark:text-primary-300'
                      : 'bg-stone-100 text-stone-400 dark:bg-stone-800 dark:text-stone-500'
                  }`}
                  animate={{ y: dragOver ? -5 : 0 }}
                >
                  <Upload className="h-10 w-10" />
                </motion.div>
                <div>
                  <h3 className="mb-2 text-xl font-semibold text-stone-900 dark:text-white">点击或拖拽文件至此处</h3>
                  <p className="mb-4 text-stone-500 dark:text-stone-400">
                    {formatHint}（{maxSizeHint}）
                  </p>
                </div>
                <motion.button
                  className="rounded-xl bg-gradient-to-r from-primary-500 to-primary-700 px-8 py-3.5 font-semibold text-white shadow-lg shadow-primary-600/30 transition-all hover:shadow-xl hover:shadow-primary-600/45"
                  whileHover={{ scale: 1.02, y: -2 }}
                  whileTap={{ scale: 0.98 }}
                  onClick={(e) => {
                    e.stopPropagation();
                    document.getElementById('file-upload-input')?.click();
                  }}
                >
                  {selectButtonText}
                </motion.button>
              </motion.div>
            )}
          </AnimatePresence>
        </div>
      </motion.div>

      {/* 名称输入框 */}
      {(showNameInput || (selectOptions && selectOptions.length > 0)) && selectedFile && (
        <motion.div
          className="dark-card mt-6 p-6"
          initial={{ opacity: 0, y: 20 }}
          animate={{ opacity: 1, y: 0 }}
        >
          {showNameInput && (
            <>
              <label className="mb-2 block text-sm font-semibold text-stone-700 dark:text-stone-300">{nameLabel}</label>
              <input
                type="text"
                value={name}
                onChange={(e) => setName(e.target.value)}
                placeholder={namePlaceholder}
                className="dark-input w-full rounded-xl px-4 py-3 focus:border-primary-500 focus:outline-none focus:ring-2 focus:ring-primary-500/30"
                disabled={uploading}
                onClick={(e) => e.stopPropagation()}
              />
            </>
          )}

          {selectOptions && selectOptions.length > 0 && (
            <div className={showNameInput ? 'mt-4' : ''}>
              <label className="mb-2 block text-sm font-semibold text-stone-700 dark:text-stone-300">{selectLabel}</label>
              <select
                value={selectValue}
                onChange={(e) => onSelectValueChange?.(e.target.value)}
                className="dark-input w-full rounded-xl px-4 py-3 focus:border-primary-500 focus:outline-none focus:ring-2 focus:ring-primary-500/30"
                disabled={uploading}
                onClick={(e) => e.stopPropagation()}
              >
                {selectOptions.map((option) => (
                  <option key={option.value} value={option.value}>
                    {option.label}
                  </option>
                ))}
              </select>
            </div>
          )}
        </motion.div>
      )}

      {/* 错误提示 */}
      <AnimatePresence>
        {error && (
          <motion.div
            initial={{ opacity: 0, y: -10 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: -10 }}
            className="mt-6 flex items-center justify-center gap-2 rounded-xl border border-red-200/80 bg-red-50/90 p-4 text-center text-red-700 dark:border-red-900/60 dark:bg-red-950/40 dark:text-red-400"
          >
            <AlertCircle className="w-5 h-5" />
            {error}
          </motion.div>
        )}
      </AnimatePresence>

      {/* 操作按钮 */}
      <div className="mt-8 flex gap-4 justify-center">
        {onBack && (
          <motion.button
            onClick={onBack}
            className="rounded-xl border border-stone-200 px-6 py-3 font-medium text-stone-600 transition-all hover:bg-stone-50 dark:border-stone-600 dark:text-stone-300 dark:hover:bg-stone-800"
            whileHover={{ scale: 1.02 }}
            whileTap={{ scale: 0.98 }}
          >
            返回
          </motion.button>
        )}
        {selectedFile && (
          <motion.button
            onClick={handleUpload}
            disabled={uploading}
            className="flex items-center gap-2 rounded-xl bg-gradient-to-r from-emerald-600 to-teal-600 px-8 py-3 font-semibold text-white shadow-lg shadow-emerald-700/25 transition-all hover:shadow-xl disabled:cursor-not-allowed disabled:opacity-60"
            whileHover={{ scale: uploading ? 1 : 1.02 }}
            whileTap={{ scale: uploading ? 1 : 0.98 }}
          >
            {uploading ? (
              <>
                <Loader2 className="w-5 h-5 animate-spin" />
                处理中...
              </>
            ) : (
              uploadButtonText
            )}
          </motion.button>
        )}
      </div>
    </motion.div>
  );
}
