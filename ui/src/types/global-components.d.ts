/**
 * Components registered globally by the Halo console host.
 *
 * They are not published by @halo-dev/components, so they cannot be imported
 * or typed locally. Declaring them keeps `vue-tsc` from rejecting the
 * templates while still allowing the host implementation to be used.
 */
declare module "vue" {
  export interface GlobalComponents {
    FormKit: any;
    SearchInput: any;
    FilterDropdown: any;
  }
}

export {};
