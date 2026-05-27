declare module "qrcode-terminal" {
  interface GenerateOptions {
    small?: boolean;
  }

  const qrcode: {
    generate(input: string, options?: GenerateOptions): void;
  };

  export default qrcode;
}
