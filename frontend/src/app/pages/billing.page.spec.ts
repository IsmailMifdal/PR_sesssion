import { isStripeUrl } from './billing.page';

describe('isStripeUrl', () => {
  it("n'accepte que les pages Stripe en HTTPS", () => {
    expect(isStripeUrl('https://checkout.stripe.com/c/pay/cs_test_1')).toBe(true);
    expect(isStripeUrl('https://billing.stripe.com/p/session/x')).toBe(true);
    expect(isStripeUrl('http://checkout.stripe.com/c/pay')).toBe(false);
    expect(isStripeUrl('https://checkout.stripe.com.evil.example/')).toBe(false);
    expect(isStripeUrl('https://evil.example/?checkout.stripe.com')).toBe(false);
    expect(isStripeUrl('javascript:alert(1)')).toBe(false);
  });
});
