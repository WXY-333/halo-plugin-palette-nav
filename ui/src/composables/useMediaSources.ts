import { ref, resolveComponent } from "vue";
import { Toast } from "@halo-dev/components";
import { consoleApiClient } from "@halo-dev/api-client";
import type { AttachmentLike } from "@halo-dev/ui-shared";
import { errorMessage } from "@/api/nav";
import { resolveAttachmentUploadTarget } from "@/api/settings";
import { resolveAttachmentPermalink } from "@/utils/attachment";

/**
 * Shared upload flow for the icon/media pickers: pick a file, upload it through
 * Halo's console attachment API, wait for the permalink and hand it back.
 *
 * IconPicker and MediaPicker used to carry separate copies of this logic, so a
 * fix to the permalink polling or the accepted formats had to be made twice.
 */
export interface AttachmentUploadOptions {
  /** Rejects the file before uploading; {@link rejectMessage} is shown. */
  validate: (file: File) => boolean;
  rejectMessage: () => string;
  successMessage: (file: File) => string;
  /** Receives the permalink and the media type reported by Halo (may be empty). */
  onUploaded: (
    permalink: string,
    mediaType: string | undefined,
    file: File
  ) => void;
}

export function useAttachmentUpload(options: AttachmentUploadOptions) {
  const uploading = ref(false);
  const fileInputRef = ref<HTMLInputElement | null>(null);

  function pickFile() {
    fileInputRef.value?.click();
  }

  async function uploadFile(file: File) {
    if (!options.validate(file)) {
      Toast.warning(options.rejectMessage());
      return;
    }
    uploading.value = true;
    try {
      // Store into the policy/group configured in 设置 → 附件存储, the same target
      // the backend uses for icons fetched from link targets. Halo's console
      // endpoint cannot carry that choice, so use the core console upload API
      // whenever a target resolves and fall back to the console endpoint otherwise.
      const target = await resolveAttachmentUploadTarget();
      const { data } = target
        ? await consoleApiClient.storage.attachment.uploadAttachment({
            file,
            policyName: target.policyName,
            groupName: target.groupName,
          })
        : await consoleApiClient.storage.attachment.uploadAttachmentForConsole({
            file,
          });
      let permalink = data.status?.permalink;
      if (!permalink) {
        permalink = await resolveAttachmentPermalink(data.metadata.name);
      }
      if (!permalink) {
        throw new Error("上传成功，但暂时无法获取访问地址，请稍后从附件库中选择");
      }
      options.onUploaded(permalink, data.spec?.mediaType, file);
      Toast.success(options.successMessage(file));
    } catch (error) {
      Toast.error(errorMessage(error));
    } finally {
      uploading.value = false;
    }
  }

  function onFileChange(event: Event) {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = "";
    if (file) {
      void uploadFile(file);
    }
  }

  return { uploading, fileInputRef, pickFile, onFileChange };
}

/**
 * Shared Halo attachment-library picker. The host component is resolved from the
 * console app's global components, which is why this has to run inside `setup()`.
 */
export function useAttachmentLibrary(
  onSelect: (attachment: AttachmentLike) => void
) {
  const AttachmentSelectorModal = resolveComponent("AttachmentSelectorModal");
  const libraryVisible = ref(false);

  function openLibrary() {
    libraryVisible.value = true;
  }

  function onAttachmentSelect(attachments: AttachmentLike[]) {
    libraryVisible.value = false;
    const first = attachments[0];
    if (first) {
      onSelect(first);
    }
  }

  return {
    AttachmentSelectorModal,
    libraryVisible,
    openLibrary,
    onAttachmentSelect,
  };
}