import {RESULTS, check, request, Permission} from 'react-native-permissions';

export const requestPermission = async (
  permission: Permission,
): Promise<boolean> => {
  const checkPermission = await check(permission);
  if (checkPermission === RESULTS.GRANTED) {
    return true;
  } else {
    const result = await request(permission);
    return result === RESULTS.GRANTED;
  }
};
