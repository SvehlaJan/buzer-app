declare module "qrcode-terminal" {
  interface GenerateOptions {
    small?: boolean;
  }

  const qrcode: {
    generate(input: string, options?: GenerateOptions, callback?: (graphic: string) => void): void;
  };

  export default qrcode;
}
