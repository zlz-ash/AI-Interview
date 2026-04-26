import { useEffect, useState } from 'react';
import { knowledgeBaseApi } from '../api/knowledgebase';
import type { UploadKnowledgeBaseResponse } from '../api/knowledgebase';
import FileUploadCard from '../components/FileUploadCard';

interface KnowledgeBaseUploadPageProps {
  onUploadComplete: (result: UploadKnowledgeBaseResponse) => void;
  onBack: () => void;
}

export default function KnowledgeBaseUploadPage({ onUploadComplete, onBack }: KnowledgeBaseUploadPageProps) {
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState('');
  const [tokenizerProfileId, setTokenizerProfileId] = useState(knowledgeBaseApi.defaultTokenizerProfileId);
  const [tokenizerProfileOptions, setTokenizerProfileOptions] = useState([
    { label: 'DashScope text-embedding-v3（推荐）', value: 'dashscope-text-embedding-v3' },
  ]);

  useEffect(() => {
    let mounted = true;
    knowledgeBaseApi.listTokenizerProfiles()
      .then((profiles) => {
        if (!mounted || profiles.length === 0) {
          return;
        }
        const options = profiles.map((profile) => ({
          label: `${profile.model} (${profile.encoding})${profile.isDefault ? '（默认）' : ''}`,
          value: profile.id,
        }));
        setTokenizerProfileOptions(options);
        const defaultProfile = profiles.find((profile) => profile.isDefault)?.id ?? profiles[0].id;
        setTokenizerProfileId(defaultProfile);
      })
      .catch(() => {
        // 忽略动态加载失败，保留本地默认 profile 作为兜底。
      });
    return () => {
      mounted = false;
    };
  }, []);

  const handleUpload = async (file: File, name?: string, selectedTokenizerProfileId?: string) => {
    setUploading(true);
    setError('');

    try {
      const profileId = selectedTokenizerProfileId || tokenizerProfileId || knowledgeBaseApi.defaultTokenizerProfileId;
      const data = await knowledgeBaseApi.uploadKnowledgeBase(file, name, undefined, profileId);
      onUploadComplete(data);
    } catch (err: unknown) {
      const errorMessage = err instanceof Error ? err.message : '上传失败，请重试';
      setError(errorMessage);
      setUploading(false);
    }
  };

  return (
    <FileUploadCard
      title="上传知识库"
      subtitle="上传文档，AI 将基于知识库内容回答您的问题"
      accept=".pdf,.doc,.docx,.txt,.md"
      formatHint="支持 PDF、DOCX、DOC、TXT、MD"
      maxSizeHint="最大 50MB"
      uploading={uploading}
      uploadButtonText="开始上传"
      selectButtonText="选择文件"
      showNameInput={true}
      nameLabel="知识库名称（可选）"
      namePlaceholder="留空则使用文件名"
      selectOptions={tokenizerProfileOptions}
      selectLabel="Tokenizer Profile"
      selectValue={tokenizerProfileId}
      onSelectValueChange={setTokenizerProfileId}
      error={error}
      onUpload={handleUpload}
      onBack={onBack}
    />
  );
}
